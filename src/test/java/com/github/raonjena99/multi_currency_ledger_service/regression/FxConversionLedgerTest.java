package com.github.raonjena99.multi_currency_ledger_service.regression;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.github.raonjena99.multi_currency_ledger_service.IntegrationTestSupport;
import com.github.raonjena99.multi_currency_ledger_service.account.domain.Account;
import com.github.raonjena99.multi_currency_ledger_service.account.domain.FxDirection;
import com.github.raonjena99.multi_currency_ledger_service.account.domain.FxPricing;
import com.github.raonjena99.multi_currency_ledger_service.account.infrastructure.AccountRepository;
import com.github.raonjena99.multi_currency_ledger_service.common.domain.CurrencyScaleResolver;
import com.github.raonjena99.multi_currency_ledger_service.common.domain.Money;
import com.github.raonjena99.multi_currency_ledger_service.common.model.AssetType;
import com.github.raonjena99.multi_currency_ledger_service.reconciliation.infrastructure.query.InternalTransactionCandidate;
import com.github.raonjena99.multi_currency_ledger_service.reconciliation.infrastructure.query.InternalTransactionQueryDao;
import com.github.raonjena99.multi_currency_ledger_service.transaction.application.LedgerQueryService;
import com.github.raonjena99.multi_currency_ledger_service.transaction.application.LedgerQueryService.CurrencyTrialBalance;
import com.github.raonjena99.multi_currency_ledger_service.transaction.application.LedgerService;
import com.github.raonjena99.multi_currency_ledger_service.transaction.application.command.FxConversionDetail;
import com.github.raonjena99.multi_currency_ledger_service.transaction.application.command.LedgerRecordingCommand;
import com.github.raonjena99.multi_currency_ledger_service.transaction.infrastructure.query.LedgerIntegrityDao;

/**
 * 환전({@code FX_CONVERSION}) 분개를 실제 DB 에 저장했을 때 시산표·정합성 점검·대사 후보 조회가 맞게 동작하는지
 * 검증합니다.
 *
 * <p>환전 분개는 회사 시스템 계정(FX, 수수료)을 함께 쓰므로, 고객 계정만 보는 점검에서는 빠지고 시산표에서는 차변 = 대변
 * 이어야 합니다. 환전은 PG 결제가 아니라서 정산 대사 후보에도 들어가면 안 됩니다.
 */
@DisplayName("회귀 테스트: 환전 분개")
class FxConversionLedgerTest extends IntegrationTestSupport {

    private static final OffsetDateTime TRADED_AT = OffsetDateTime.now(ZoneOffset.UTC);
    private static final YearMonth MONTH = YearMonth.from(TRADED_AT);
    private static final UUID SYSTEM_FX = new UUID(0, 0);
    private static final UUID SYSTEM_FEE = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Autowired private LedgerService ledgerService;
    @Autowired private LedgerQueryService queryService;
    @Autowired private LedgerIntegrityDao integrityDao;
    @Autowired private InternalTransactionQueryDao candidateQueryDao;
    @Autowired private AccountRepository accountRepository;
    @Autowired private JdbcTemplate jdbc;

    @AfterEach
    void tearDown() {
        jdbc.execute("TRUNCATE TABLE transaction_entries, transactions CASCADE");
        deleteTestAccounts();
    }

    private UUID account() {
        UUID id = UUID.randomUUID();
        accountRepository.saveAndFlush(Account.open(id, "FX_LEDGER_USER", "KRW"));
        return id;
    }

    /** 1,322,750 원으로 USD 1,000 을 산다. 시세 가치 1,300,000, 스프레드 22,750. */
    private UUID buyUsd(UUID accountId) {
        return record(accountId, "BUY_FOREIGN", "1322750", "1300000", null);
    }

    /** USD 1,000 을 1,277,250 원에 판다. 평균 단가 1,322.75 라 고객 손실 45,500, 스프레드 22,750. */
    private UUID sellUsd(UUID accountId) {
        return record(accountId, "SELL_FOREIGN", "1277250", "1300000", "1322.75");
    }

    private UUID record(UUID accountId, String direction, String baseAmount, String marketValue, String averageCost) {
        UUID tradeId = UUID.randomUUID();
        BigDecimal base = new BigDecimal(baseAmount);
        ledgerService.recordDoubleEntry(new LedgerRecordingCommand(tradeId, accountId, "USD", AssetType.FIAT, "KRW",
                "KRW", "FX_CONVERSION", Money.of("1000", AssetType.FIAT, "USD"), base.divide(new BigDecimal("1000")),
                BigDecimal.ONE, BigDecimal.ONE, averageCost == null ? null : new BigDecimal(averageCost), false,
                TRADED_AT, new FxConversionDetail(direction, base, new BigDecimal(marketValue))));
        return tradeId;
    }

    private List<Map<String, Object>> rows(UUID tradeId) {
        return jdbc.queryForList("SELECT account_id, entry_type, asset_code, quantity, quantity_currency, amount, "
                + "amount_currency FROM transaction_entries WHERE transaction_id = ? ORDER BY id", tradeId);
    }

    private CurrencyTrialBalance krw() {
        return queryService.trialBalance(MONTH).currencies().stream()
                .filter(c -> "KRW".equals(c.currency())).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("환전 분개는 고객·회사 FX·수수료 세 계정에 저장된다")
    void persistsCustomerCompanyAndSpreadEntries() {
        UUID accountId = account();
        UUID tradeId = buyUsd(accountId);

        List<Map<String, Object>> entries = rows(tradeId);

        assertThat(entries).extracting(r -> r.get("account_id")).containsOnly(accountId, SYSTEM_FX, SYSTEM_FEE);
        assertThat(entries).filteredOn(r -> r.get("account_id").equals(SYSTEM_FEE)).singleElement().satisfies(r -> {
            assertThat(r.get("entry_type")).isEqualTo("CREDIT");
            assertThat(r.get("asset_code")).isEqualTo("SYSTEM_FX_SPREAD");
            assertThat((BigDecimal) r.get("amount")).isEqualByComparingTo("22750");
        });
    }

    @Test
    @DisplayName("시산표는 외화 매수와 매도 후에도 차변 = 대변 이다")
    void trialBalanceBalancesAfterAConversionRoundTrip() {
        UUID accountId = account();
        buyUsd(accountId);
        sellUsd(accountId);

        CurrencyTrialBalance krw = krw();

        assertThat(krw.balanced()).isTrue();
        assertThat(krw.debitTotal()).isEqualByComparingTo(krw.creditTotal());
        assertThat(krw.realizedPnlTotal()).as("고객의 왕복 환차손").isEqualByComparingTo("-45500");
    }

    @Test
    @DisplayName("정합성 점검은 고객 계정의 (계좌, 통화) 쌍만 비교하고 회사 시스템 계정 분개는 뺀다")
    void integrityPairsCoverOnlyTheCustomerCurrencies() {
        UUID accountId = account();
        buyUsd(accountId);

        // 이 계좌에는 월차 원장(잔고)이 없으므로 분개가 있는 쌍이 모두 차이로 나온다. 비교 대상은 USD, KRW 두 쌍이다.
        assertThat(integrityDao.findDifferences())
                .extracting(d -> d.accountId() + ":" + d.assetCode())
                .containsExactlyInAnyOrder(accountId + ":USD", accountId + ":KRW");
    }

    @Test
    @DisplayName("무작위 환전 금액도 DB 제약(금액 ≈ 수량 × 단가 × 환율)을 지키고 모든 거래가 차변 = 대변 이다")
    void randomConversionsSatisfyTheDatabaseConstraintsAndBalance() {
        // 고정 시드: 통화 자릿수(KRW 0, USD 2, BHD 3)와 시세 규모(1e-4 ~ 1e3)가 다른 조합을 섞어 반올림 경계를 훑는다.
        Random random = new Random(20261012L);
        String[] currencies = {"KRW", "USD", "EUR", "JPY", "BHD"};
        BigDecimal[] spreads = {new BigDecimal("0.0175"), new BigDecimal("0.02"), new BigDecimal("0.03"),
                new BigDecimal("0.05")};
        UUID accountId = account();
        int recorded = 0;

        for (int i = 0; i < 300; i++) {
            String base = currencies[random.nextInt(currencies.length)];
            String foreign;
            do {
                foreign = currencies[random.nextInt(currencies.length)];
            } while (foreign.equals(base));
            int baseScale = CurrencyScaleResolver.resolveScale(AssetType.FIAT, base);
            int foreignScale = CurrencyScaleResolver.resolveScale(AssetType.FIAT, foreign);
            BigDecimal market = BigDecimal.valueOf(Math.pow(10, random.nextDouble() * 7 - 4))
                    .setScale(12, RoundingMode.HALF_EVEN);
            BigDecimal spread = spreads[random.nextInt(spreads.length)];
            BigDecimal preferential = BigDecimal.valueOf(random.nextInt(9001), 4);
            boolean buying = random.nextBoolean();
            String context = String.format("i=%d base=%s foreign=%s market=%s buying=%s", i, base, foreign,
                    market.toPlainString(), buying);

            BigDecimal baseAmount;
            BigDecimal foreignAmount;
            BigDecimal marketValue;
            BigDecimal averageCost = null;
            if (buying) {
                baseAmount = BigDecimal.valueOf(random.nextDouble()).movePointRight(1 + random.nextInt(9))
                        .setScale(baseScale, RoundingMode.DOWN);
                if (baseAmount.signum() <= 0) {
                    continue;
                }
                foreignAmount = FxPricing.price(FxDirection.BUY_FOREIGN, baseAmount, market, spread, preferential,
                        foreignScale).receivedAmount();
                if (foreignAmount.signum() <= 0) {
                    continue;
                }
                marketValue = foreignAmount.multiply(market).setScale(baseScale, RoundingMode.DOWN);
            } else {
                foreignAmount = BigDecimal.valueOf(random.nextDouble()).movePointRight(random.nextInt(8))
                        .setScale(foreignScale, RoundingMode.DOWN);
                if (foreignAmount.signum() <= 0) {
                    continue;
                }
                baseAmount = FxPricing.price(FxDirection.SELL_FOREIGN, foreignAmount, market, spread, preferential,
                        baseScale).receivedAmount();
                if (baseAmount.signum() <= 0) {
                    continue;
                }
                marketValue = foreignAmount.multiply(market).setScale(baseScale, RoundingMode.UP);
                averageCost = market.multiply(BigDecimal.valueOf(0.7 + random.nextDouble() * 0.6))
                        .setScale(18, RoundingMode.HALF_EVEN);
            }
            assertThat(buying ? baseAmount.subtract(marketValue) : marketValue.subtract(baseAmount))
                    .as("스프레드 수익은 음수가 아니다: " + context).isNotNegative();

            LedgerRecordingCommand command = new LedgerRecordingCommand(UUID.randomUUID(), accountId, foreign,
                    AssetType.FIAT, base, base, "FX_CONVERSION", Money.of(foreignAmount, AssetType.FIAT, foreign),
                    baseAmount.divide(foreignAmount, 18, RoundingMode.HALF_EVEN), market, BigDecimal.ONE, averageCost,
                    false, TRADED_AT, new FxConversionDetail(buying ? "BUY_FOREIGN" : "SELL_FOREIGN", baseAmount,
                            marketValue));
            assertThatCode(() -> ledgerService.recordDoubleEntry(command)).as(context).doesNotThrowAnyException();
            recorded++;
        }

        assertThat(recorded).as("실제로 기록된 환전 수").isGreaterThan(150);
        // 저장된 모든 환전 거래가 DB 에서도 차변 = 대변 이다.
        assertThat(jdbc.queryForList("SELECT transaction_id FROM transaction_entries GROUP BY transaction_id "
                + "HAVING SUM(CASE WHEN entry_type = 'DEBIT' THEN amount ELSE -amount END) <> 0", UUID.class))
                .as("차변과 대변이 어긋난 거래").isEmpty();
    }

    @Test
    @DisplayName("환전은 PG 결제가 아니므로 대사 후보에 들어가지 않는다")
    void conversionsAreNotReconciliationCandidates() {
        UUID accountId = account();
        UUID buy = buyUsd(accountId);
        UUID sell = sellUsd(accountId);

        List<InternalTransactionCandidate> candidates = candidateQueryDao
                .fetchCandidatesForPeriod(TRADED_AT.minusDays(1), TRADED_AT.plusDays(1));

        assertThat(candidates).extracting(InternalTransactionCandidate::transactionId).doesNotContain(buy, sell);
    }
}
