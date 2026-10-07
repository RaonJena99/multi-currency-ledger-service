package com.github.raonjena99.multi_currency_ledger_service.regression;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.github.raonjena99.multi_currency_ledger_service.IntegrationTestSupport;
import com.github.raonjena99.multi_currency_ledger_service.account.domain.Account;
import com.github.raonjena99.multi_currency_ledger_service.account.infrastructure.AccountRepository;
import com.github.raonjena99.multi_currency_ledger_service.common.domain.Money;
import com.github.raonjena99.multi_currency_ledger_service.common.model.AssetType;
import com.github.raonjena99.multi_currency_ledger_service.reconciliation.infrastructure.query.InternalTransactionCandidate;
import com.github.raonjena99.multi_currency_ledger_service.reconciliation.infrastructure.query.InternalTransactionQueryDao;
import com.github.raonjena99.multi_currency_ledger_service.transaction.application.LedgerQueryService;
import com.github.raonjena99.multi_currency_ledger_service.transaction.application.LedgerQueryService.CurrencyTrialBalance;
import com.github.raonjena99.multi_currency_ledger_service.transaction.application.LedgerService;
import com.github.raonjena99.multi_currency_ledger_service.transaction.application.command.LedgerRecordingCommand;
import com.github.raonjena99.multi_currency_ledger_service.transaction.infrastructure.query.LedgerIntegrityDao;
import com.github.raonjena99.multi_currency_ledger_service.transaction.infrastructure.query.LedgerQueryDao.EntryView;

/**
 * 실현 손익이 고객 계정의 별도 분개({@code REALIZED_PNL_*})로 기록되고, 그 분개가 시산표·정합성 점검·대사 후보
 * 조회를 왜곡하지 않는지 실제 DB 로 검증합니다.
 *
 * <p>손익 분개는 잔고 행이 없고 거래의 원 금액도 아닙니다. 그래서 잔고와 분개 누계를 비교하는 점검과 PG 정산과
 * 맞춰 보는 대사 후보에서는 빠져야 하고, 시산표에서는 차변 = 대변 이 성립해야 합니다.
 */
@DisplayName("회귀 테스트: 실현 손익 분개")
class RealizedPnlEntryTest extends IntegrationTestSupport {

    private static final OffsetDateTime TRADED_AT = OffsetDateTime.now(ZoneOffset.UTC);
    private static final YearMonth MONTH = YearMonth.from(TRADED_AT);

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
        accountRepository.saveAndFlush(Account.open(id, "PNL_USER", "KRW"));
        return id;
    }

    /** BTC {@code quantity} 개를 KRW {@code price} 에 매도한다. 평균 단가는 {@code averageCost}. */
    private UUID sell(UUID accountId, String quantity, String price, String averageCost) {
        UUID tradeId = UUID.randomUUID();
        ledgerService.recordDoubleEntry(new LedgerRecordingCommand(tradeId, accountId, "BTC", AssetType.CRYPTO,
                "KRW", "KRW", "SELL", Money.of(quantity, AssetType.CRYPTO, "BTC"), new BigDecimal(price),
                new BigDecimal(price), BigDecimal.ONE, new BigDecimal(averageCost), false, TRADED_AT));
        return tradeId;
    }

    private List<Map<String, Object>> pnlRows(UUID tradeId) {
        return jdbc.queryForList("SELECT account_id, entry_type, asset_code, quantity, quantity_currency, unit_price, "
                + "exchange_rate, amount, amount_currency, realized_pnl FROM transaction_entries "
                + "WHERE transaction_id = ? ORDER BY id", tradeId).stream()
                .filter(r -> ((String) r.get("asset_code")).startsWith("REALIZED_PNL_"))
                .toList();
    }

    private CurrencyTrialBalance krw() {
        return queryService.trialBalance(MONTH).currencies().stream()
                .filter(c -> "KRW".equals(c.currency())).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("매도 이익은 고객 계정의 대변 손익 분개로 저장된다")
    void profitableSellPersistsATradingPnlEntryForTheCustomer() {
        UUID accountId = account();

        // 평균 단가 800 에 사서 1000 에 2개 매도 → 실현 이익 400
        UUID tradeId = sell(accountId, "2", "1000", "800");

        assertThat(pnlRows(tradeId)).singleElement().satisfies(r -> {
            assertThat(r.get("account_id")).isEqualTo(accountId);
            assertThat(r.get("entry_type")).isEqualTo("CREDIT");
            assertThat(r.get("asset_code")).isEqualTo("REALIZED_PNL_TRADING");
            assertThat((BigDecimal) r.get("amount")).isEqualByComparingTo("400");
            assertThat(r.get("amount_currency")).isEqualTo("KRW");
            assertThat((BigDecimal) r.get("quantity")).isEqualByComparingTo("400");
            assertThat(r.get("quantity_currency")).isEqualTo("KRW");
            assertThat((BigDecimal) r.get("unit_price")).isEqualByComparingTo("1");
            assertThat((BigDecimal) r.get("exchange_rate")).isEqualByComparingTo("1");
            assertThat((BigDecimal) r.get("realized_pnl")).isEqualByComparingTo("0");
        });
    }

    @Test
    @DisplayName("외화 출금의 환차익은 환차손익(FX) 분개로 저장된다")
    void foreignWithdrawalPersistsAnFxPnlEntry() {
        UUID accountId = account();
        UUID tradeId = UUID.randomUUID();

        // 1,300 원에 들어온 USD 40 을 1,400 원일 때 출금 → 환차익 4,000
        ledgerService.recordDoubleEntry(new LedgerRecordingCommand(tradeId, accountId, "USD", AssetType.FIAT, "USD",
                "KRW", "WITHDRAWAL", Money.of("40", AssetType.FIAT, "USD"), BigDecimal.ONE, BigDecimal.ONE,
                new BigDecimal("1400"), new BigDecimal("1300"), false, TRADED_AT));

        assertThat(pnlRows(tradeId)).singleElement().satisfies(r -> {
            assertThat(r.get("account_id")).isEqualTo(accountId);
            assertThat(r.get("entry_type")).isEqualTo("CREDIT");
            assertThat(r.get("asset_code")).isEqualTo("REALIZED_PNL_FX");
            assertThat((BigDecimal) r.get("amount")).isEqualByComparingTo("4000");
        });
    }

    @Test
    @DisplayName("시산표는 실현 손익을 따로 더하지 않아도 차변 = 대변 이다")
    void trialBalanceBalancesWithoutAddingPnl() {
        UUID accountId = account();
        sell(accountId, "2", "1000", "800");

        CurrencyTrialBalance krw = krw();

        // 차변 2,000(수취) / 대변 1,600(원가) + 400(이익 분개)
        assertThat(krw.debitTotal()).isEqualByComparingTo("2000");
        assertThat(krw.creditTotal()).isEqualByComparingTo("2000");
        assertThat(krw.realizedPnlTotal()).as("참고값: 이익 분개 − 손실 분개").isEqualByComparingTo("400");
        assertThat(krw.balanced()).isTrue();
    }

    @Test
    @DisplayName("손실은 차변 분개이므로 실현 손익 합계는 음수가 된다")
    void lossIsADebitEntryAndNegativeInTheTotal() {
        UUID accountId = account();
        sell(accountId, "2", "1000", "1200");

        CurrencyTrialBalance krw = krw();

        // 차변 2,000(수취) + 400(손실 분개) / 대변 2,400(원가)
        assertThat(krw.debitTotal()).isEqualByComparingTo("2400");
        assertThat(krw.creditTotal()).isEqualByComparingTo("2400");
        assertThat(krw.realizedPnlTotal()).isEqualByComparingTo("-400");
        assertThat(krw.balanced()).isTrue();
    }

    @Test
    @DisplayName("정합성 점검은 잔고 행이 없는 손익 분개를 (계좌, 자산) 비교에서 뺀다")
    void integrityPairsSkipPnlEntries() {
        UUID accountId = account();
        sell(accountId, "2", "1000", "800");

        // 이 계좌에는 월차 원장(잔고)이 없으므로 분개가 있는 쌍이 모두 차이로 나온다. 그래도 손익 분개는
        // 잔고가 아니라 손익이므로 쌍이 아니다. 비교 대상은 BTC, KRW 두 쌍뿐이어야 한다.
        assertThat(integrityDao.findDifferences())
                .filteredOn(d -> d.accountId().equals(accountId))
                .extracting(d -> d.assetCode())
                .containsExactlyInAnyOrder("BTC", "KRW");
    }

    @Test
    @DisplayName("대사 후보의 금액은 손익 분개를 더하지 않는다")
    void reconciliationCandidateAmountExcludesPnlEntries() {
        UUID accountId = account();
        UUID tradeId = sell(accountId, "2", "1000", "800");

        List<InternalTransactionCandidate> candidates = candidateQueryDao
                .fetchCandidatesForPeriod(TRADED_AT.minusDays(1), TRADED_AT.plusDays(1));

        // 지금처럼 원 분개(자산 대변: 원가 1,600)의 금액이 후보 금액이다. 손익 400 을 더하면 2,000 이 된다.
        assertThat(candidates).filteredOn(c -> c.transactionId().equals(tradeId)).singleElement()
                .satisfies(c -> assertThat(c.amount().getAmount()).isEqualByComparingTo("1600"));
    }

    @Test
    @DisplayName("거래 상세에는 고객의 손익 분개가 함께 보인다")
    void transactionDetailShowsThePnlEntry() {
        UUID accountId = account();
        UUID tradeId = sell(accountId, "2", "1000", "800");

        List<EntryView> entries = queryService.findTransaction(accountId, tradeId).entries();

        assertThat(entries).filteredOn(e -> e.assetCode().equals("REALIZED_PNL_TRADING")).singleElement()
                .satisfies(e -> {
                    assertThat(e.entryType()).isEqualTo("CREDIT");
                    assertThat(e.amount()).isEqualByComparingTo("400");
                });
    }
}
