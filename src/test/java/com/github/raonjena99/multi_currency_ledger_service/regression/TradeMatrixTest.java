package com.github.raonjena99.multi_currency_ledger_service.regression;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import com.github.raonjena99.multi_currency_ledger_service.IntegrationTestSupport;
import com.github.raonjena99.multi_currency_ledger_service.account.AccountApi;
import com.github.raonjena99.multi_currency_ledger_service.account.application.AccountTradeFacade;
import com.github.raonjena99.multi_currency_ledger_service.account.domain.Account;
import com.github.raonjena99.multi_currency_ledger_service.account.domain.MonthlyAccountLedger;
import com.github.raonjena99.multi_currency_ledger_service.account.infrastructure.AccountRepository;
import com.github.raonjena99.multi_currency_ledger_service.account.infrastructure.MonthlyAccountLedgerRepository;
import com.github.raonjena99.multi_currency_ledger_service.common.domain.CurrencyScaleResolver;
import com.github.raonjena99.multi_currency_ledger_service.common.domain.Money;
import com.github.raonjena99.multi_currency_ledger_service.common.model.AssetType;
import com.github.raonjena99.multi_currency_ledger_service.common.outbox.OutboxRelayWorker;
import com.github.raonjena99.multi_currency_ledger_service.common.port.ExchangeRateProvider;

/**
 * 거래 주 경로의 <b>전수 매트릭스</b> 검증입니다.
 *
 * <p>{@link LedgerMatrixTest} 가 원장 분개 단독을 검증하는 반면, 이 테스트는
 * <b>잔고와 원장의 경계</b>를 검증합니다. 지금까지 이 경계에서 결함이 반복해서 나왔습니다.
 * <ul>
 *   <li>반올림 방향을 잔고에만 적용하고 원장에는 적용하지 않음</li>
 *   <li>읽기 기준을 {@code MAX(ledger_month)} 로 바꾸고 쓰기 기준은 그대로 둠</li>
 *   <li>거래 시점 환율을 잔고에만 쓰고 원장은 재조회</li>
 * </ul>
 * 공통점은 <b>한쪽만 바꿨다</b>는 것입니다. 그래서 매 조합마다 잔고 변화량과 원장 기록을
 * 서로 대조합니다.
 */
@DisplayName("전수 검증: 거래 주 경로 매트릭스")
class TradeMatrixTest extends IntegrationTestSupport {

    private static final String MONTH = OffsetDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM"));

    @Autowired private AccountTradeFacade tradeFacade;
    @Autowired private AccountApi accountApi;
    @Autowired private AccountRepository accountRepository;
    @Autowired private MonthlyAccountLedgerRepository ledgerRepository;
    @Autowired private OutboxRelayWorker relayWorker;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TransactionTemplate tx;

    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private ExchangeRateProvider exchangeRateProvider;

    @AfterEach
    void tearDown() {
        jdbc.execute("TRUNCATE TABLE outbox_events, transaction_entries, transactions, "
                + "monthly_account_ledgers, idempotency_records CASCADE");
        deleteTestAccounts();
    }

    /**
     * 거래 조합.
     *
     * @param side            BUY 또는 SELL
     * @param paymentCurrency 결제 통화
     * @param baseCurrency    계좌 기준 통화
     * @param fxRate          결제 통화 → 기준 통화 환율 (같으면 사용되지 않음)
     * @param quantity        거래 수량
     * @param unitPrice       결제 통화 기준 단가
     */
    record Trade(String side, String paymentCurrency, String baseCurrency,
                 String fxRate, String quantity, String unitPrice) {
        @Override
        public String toString() {
            return "%s %s→%s fx=%s qty=%s price=%s"
                    .formatted(side, paymentCurrency, baseCurrency, fxRate, quantity, unitPrice);
        }
    }

    static Stream<Trade> trades() {
        String[][] pairs = {
                {"KRW", "KRW", "1"},
                {"USD", "USD", "1"},
                {"USD", "KRW", "1300"},
                {"USD", "JPY", "157"},
                {"KRW", "USD", "0.00077"},
        };
        // 단가는 시장 시세와 같게 두어(편차 0%) 단가 검증을 통과시키고, 반올림 경계를 노린다.
        String[][] amounts = {
                {"1", "100"},
                {"2", "10.5"},
                {"0.5", "1.01"},      // 결제 통화 반올림 강제
                {"3", "33.33"},
                {"0.25", "4.04"},
        };

        List<Trade> out = new ArrayList<>();
        for (String[] p : pairs) {
            for (String[] a : amounts) {
                out.add(new Trade("BUY", p[0], p[1], p[2], a[0], a[1]));
                out.add(new Trade("SELL", p[0], p[1], p[2], a[0], a[1]));
            }
        }
        return out.stream();
    }

    private static int scaleOf(String currency) {
        return CurrencyScaleResolver.resolveScale(AssetType.FIAT, currency);
    }

    private BigDecimal fiatBalance(UUID accountId, String currency) {
        return ledgerRepository.findByAccountIdAndAssetCodeAndLedgerMonth(accountId, currency, MONTH)
                .orElseThrow().getBalance().getAmount();
    }

    private BigDecimal assetBalance(UUID accountId) {
        return ledgerRepository.findByAccountIdAndAssetCodeAndLedgerMonth(accountId, "BTC", MONTH)
                .orElseThrow().getBalance().getAmount();
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("trades")
    void matrix(Trade t) {
        UUID accountId = UUID.randomUUID();
        BigDecimal fxRate = new BigDecimal(t.fxRate());
        BigDecimal qty = new BigDecimal(t.quantity());
        BigDecimal price = new BigDecimal(t.unitPrice());

        // 넉넉한 초기 잔고를 심는다.
        tx.execute(s -> {
            accountRepository.save(Account.open(accountId, "TRADE_MATRIX", t.baseCurrency()));
            MonthlyAccountLedger fiat = MonthlyAccountLedger.initialize(
                    accountId, t.paymentCurrency(), AssetType.FIAT, MONTH, t.baseCurrency());
            fiat.addBalance(Money.of("1000000", AssetType.FIAT, t.paymentCurrency()), BigDecimal.ONE);
            ledgerRepository.save(fiat);

            MonthlyAccountLedger btc = MonthlyAccountLedger.initialize(
                    accountId, "BTC", AssetType.CRYPTO, MONTH, t.baseCurrency());
            btc.addBalance(Money.of("100", AssetType.CRYPTO, "BTC"), BigDecimal.ONE);
            ledgerRepository.save(btc);
            return null;
        });

        // 시세는 단가와 동일하게(편차 0%) 두어 단가 검증을 통과시킨다.
        org.mockito.Mockito.when(exchangeRateProvider.getExchangeRate("BTC", t.paymentCurrency()))
                .thenReturn(new ExchangeRateProvider.ExchangeRate(price, false));
        org.mockito.Mockito.when(exchangeRateProvider.getExchangeRate(t.paymentCurrency(), t.baseCurrency()))
                .thenReturn(new ExchangeRateProvider.ExchangeRate(fxRate, false));

        BigDecimal fiatBefore = fiatBalance(accountId, t.paymentCurrency());
        BigDecimal assetBefore = assetBalance(accountId);

        Money quantity = Money.of(t.quantity(), AssetType.CRYPTO, "BTC");
        String key = "matrix-" + UUID.randomUUID();

        BigDecimal rawNotional = price.multiply(qty);
        BigDecimal minUnit = BigDecimal.ONE.movePointLeft(scaleOf(t.paymentCurrency()));

        // 결제 대금이 통화 최소 단위보다 작으면 거래 자체가 거부되어야 한다.
        // 이 경우를 스킵하지 않고 거부 동작을 검증한다. 반올림이 통화를 만들거나 없애는 경로다.
        if (rawNotional.abs().compareTo(minUnit) < 0) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> {
                        if ("BUY".equals(t.side())) {
                            tradeFacade.buyAsset(key, accountId, "BTC", AssetType.CRYPTO,
                                    t.paymentCurrency(), quantity, price);
                        } else {
                            tradeFacade.sellAsset(key, accountId, "BTC", AssetType.CRYPTO,
                                    t.paymentCurrency(), quantity, price);
                        }
                    })
                    .as("최소 단위 미만 대금(%s %s)은 거부되어야 한다: %s", rawNotional, t.paymentCurrency(), t)
                    .isInstanceOf(com.github.raonjena99.multi_currency_ledger_service.common.exception
                            .BelowMinimumNotionalException.class);

            assertThat(fiatBalance(accountId, t.paymentCurrency()))
                    .as("거부된 거래는 잔고를 건드리지 않아야 한다: %s", t)
                    .isEqualByComparingTo(fiatBefore);
            assertThat(assetBalance(accountId))
                    .as("거부된 거래는 자산 잔고를 건드리지 않아야 한다: %s", t)
                    .isEqualByComparingTo(assetBefore);
            return;
        }

        UUID tradeId = "BUY".equals(t.side())
                ? tradeFacade.buyAsset(key, accountId, "BTC", AssetType.CRYPTO, t.paymentCurrency(), quantity, price)
                : tradeFacade.sellAsset(key, accountId, "BTC", AssetType.CRYPTO, t.paymentCurrency(), quantity, price);

        BigDecimal fiatAfter = fiatBalance(accountId, t.paymentCurrency());
        BigDecimal assetAfter = assetBalance(accountId);

        // ---- 1) 자산 수량 변화는 정확히 거래 수량이어야 한다 ----
        BigDecimal assetDelta = assetAfter.subtract(assetBefore);
        assertThat(assetDelta)
                .as("자산 잔고 변화량: %s", t)
                .isEqualByComparingTo("BUY".equals(t.side()) ? qty : qty.negate());

        // ---- 2) 결제 통화 변화량은 방향성 반올림을 따라야 한다 ----
        BigDecimal raw = price.multiply(qty);
        BigDecimal expectedFiatDelta = "BUY".equals(t.side())
                // 고객이 지불 → 올림 (청구액이 깎이지 않는다)
                ? raw.setScale(scaleOf(t.paymentCurrency()), RoundingMode.UP).negate()
                // 고객이 수취 → 내림 (통화가 창출되지 않는다)
                : raw.setScale(scaleOf(t.paymentCurrency()), RoundingMode.DOWN);

        assertThat(fiatAfter.subtract(fiatBefore))
                .as("결제 통화 잔고 변화량. 방향이 틀리면 반올림이 통화를 만들거나 없앤다: %s", t)
                .isEqualByComparingTo(expectedFiatDelta);

        // ---- 3) 원장이 잔고와 같은 금액·같은 환율로 기록되어야 한다 ----
        relayWorker.relayOutboxEvents();

        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(300)).untilAsserted(() ->
                assertThat(jdbc.queryForObject("SELECT count(*) FROM transactions WHERE id = ?",
                        Integer.class, tradeId))
                        .as("원장이 기록되어야 한다: %s", t)
                        .isEqualTo(1));

        // 3a) 원장에 기록된 결제 통화 수량 == 잔고가 실제로 움직인 금액
        BigDecimal ledgerFiatQuantity = jdbc.queryForObject(
                "SELECT quantity FROM transaction_entries WHERE transaction_id = ? AND asset_code = ?",
                BigDecimal.class, tradeId, t.paymentCurrency());
        assertThat(ledgerFiatQuantity)
                .as("원장 기록 금액이 잔고 이동 금액과 달라서는 안 된다: %s", t)
                .isEqualByComparingTo(expectedFiatDelta.abs());

        // 3b) 모든 엔트리가 거래 시점 환율을 사용해야 한다 (플러그 엔트리는 환율 1)
        List<BigDecimal> rates = jdbc.queryForList(
                "SELECT exchange_rate FROM transaction_entries "
                        + "WHERE transaction_id = ? AND asset_code NOT LIKE 'SYSTEM\\_%'",
                BigDecimal.class, tradeId);
        assertThat(rates)
                .as("원장 환율이 거래 시점 환율과 같아야 한다: %s", t)
                .isNotEmpty()
                .allSatisfy(r -> assertThat(r).isEqualByComparingTo(fxRate));

        // ---- 4) 보고 잔고가 실제 원장 행과 일치해야 한다 (읽기/쓰기 기준 불일치 방지) ----
        var reported = accountApi.getBalances(accountId);
        assertThat(reported)
                .as("보고 잔고에 BTC 가 포함되어야 한다: %s", t)
                .anySatisfy(b -> {
                    if ("BTC".equals(b.assetCode())) {
                        assertThat(b.totalQuantity())
                                .as("보고 잔고가 쓰기된 행과 달라서는 안 된다: %s", t)
                                .isEqualByComparingTo(assetAfter);
                    }
                });

        // ---- 5) 대차 일치 ----
        BigDecimal debit = jdbc.queryForObject(
                "SELECT COALESCE(SUM(amount),0) FROM transaction_entries "
                        + "WHERE transaction_id = ? AND entry_type = 'DEBIT'", BigDecimal.class, tradeId);
        BigDecimal credit = jdbc.queryForObject(
                "SELECT COALESCE(SUM(amount),0) + COALESCE(SUM(realized_pnl),0) FROM transaction_entries "
                        + "WHERE transaction_id = ? AND entry_type = 'CREDIT'", BigDecimal.class, tradeId);
        BigDecimal creditPnlOnDebit = jdbc.queryForObject(
                "SELECT COALESCE(SUM(realized_pnl),0) FROM transaction_entries "
                        + "WHERE transaction_id = ? AND entry_type = 'DEBIT'", BigDecimal.class, tradeId);
        assertThat(debit)
                .as("차변 == 대변 + 실현손익: %s", t)
                .isEqualByComparingTo(credit.add(creditPnlOnDebit));
    }
}
