package com.github.raonjena99.multi_currency_ledger_service.regression;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import com.github.raonjena99.multi_currency_ledger_service.IntegrationTestSupport;
import com.github.raonjena99.multi_currency_ledger_service.account.application.AccountTradeFacade;
import com.github.raonjena99.multi_currency_ledger_service.account.domain.Account;
import com.github.raonjena99.multi_currency_ledger_service.account.domain.MonthlyAccountLedger;
import com.github.raonjena99.multi_currency_ledger_service.account.infrastructure.AccountRepository;
import com.github.raonjena99.multi_currency_ledger_service.account.infrastructure.MonthlyAccountLedgerRepository;
import com.github.raonjena99.multi_currency_ledger_service.common.domain.CurrencyScaleResolver;
import com.github.raonjena99.multi_currency_ledger_service.common.domain.Money;
import com.github.raonjena99.multi_currency_ledger_service.common.exception.BelowMinimumNotionalException;
import com.github.raonjena99.multi_currency_ledger_service.common.exception.InsufficientBalanceException;
import com.github.raonjena99.multi_currency_ledger_service.common.model.AssetType;
import com.github.raonjena99.multi_currency_ledger_service.common.port.ExchangeRateProvider;

/**
 * 반올림이 통화를 만들거나 없애지 않는지 검증합니다.
 *
 * <p>KRW/JPY 는 ISO 4217 스케일이 0 입니다. 통화 스케일 정규화를 HALF_EVEN 으로 하면
 * 0.4 원짜리 거래는 지불 0 원(무상 취득), 0.6 원짜리는 수취 1 원(통화 증식)이 됩니다.
 */
@DisplayName("회귀 테스트: 반올림이 통화를 만들지 않는다")
class MoneyConservationTest extends IntegrationTestSupport {

    private static final String MONTH = OffsetDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM"));

    @Autowired private AccountTradeFacade tradeFacade;
    @Autowired private AccountRepository accountRepository;
    @Autowired private MonthlyAccountLedgerRepository ledgerRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TransactionTemplate txTemplate;

    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private ExchangeRateProvider exchangeRateProvider;

    @AfterEach
    void tearDown() {
        jdbcTemplate.execute("TRUNCATE TABLE outbox_events, transaction_entries, transactions, "
                + "monthly_account_ledgers, idempotency_records CASCADE");
        deleteTestAccounts();
    }

    private UUID seed(String fiatBalance) {
        UUID accountId = UUID.randomUUID();
        txTemplate.execute(status -> {
            accountRepository.save(Account.open(accountId, "ROUNDING", "KRW"));
            MonthlyAccountLedger fiat = MonthlyAccountLedger.initialize(accountId, "KRW", AssetType.FIAT, MONTH, "KRW");
            if (new BigDecimal(fiatBalance).signum() > 0) {
                fiat.addBalance(Money.of(fiatBalance, AssetType.FIAT, "KRW"), BigDecimal.ONE);
            }
            ledgerRepository.save(fiat);
            ledgerRepository.save(MonthlyAccountLedger.initialize(accountId, "BTC", AssetType.CRYPTO, MONTH, "KRW"));
            return null;
        });
        return accountId;
    }

    private void giveBtc(UUID accountId, String quantity) {
        txTemplate.execute(status -> {
            MonthlyAccountLedger btc = ledgerRepository
                    .findByAccountIdAndAssetCodeAndLedgerMonth(accountId, "BTC", MONTH).orElseThrow();
            btc.addBalance(Money.of(quantity, AssetType.CRYPTO, "BTC"), BigDecimal.ONE);
            ledgerRepository.save(btc);
            return null;
        });
    }

    private void mockRate(String rate) {
        org.mockito.Mockito.when(exchangeRateProvider.getExchangeRate(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(new ExchangeRateProvider.ExchangeRate(new BigDecimal(rate), false));
    }

    private BigDecimal balanceOf(UUID accountId, String assetCode) {
        return ledgerRepository.findByAccountIdAndAssetCodeAndLedgerMonth(accountId, assetCode, MONTH)
                .orElseThrow().getBalance().getAmount();
    }

    @Test
    @DisplayName("KRW 최소 단위는 1 이며, 그보다 작은 대금의 매수는 거부된다")
    void rejects_purchase_below_minimum_unit() {
        assertThat(CurrencyScaleResolver.resolveScale(AssetType.FIAT, "KRW")).isZero();
        assertThat(CurrencyScaleResolver.minimumUnit(AssetType.FIAT, "KRW")).isEqualByComparingTo("1");

        UUID accountId = seed("1000");
        mockRate("0.4");

        // 1 BTC x 0.4 KRW = 0.4 KRW. 예전에는 0 원으로 반올림되어 자산을 공짜로 얻을 수 있었다.
        assertThatThrownBy(() -> tradeFacade.buyAsset("dust-" + UUID.randomUUID(), accountId, "BTC",
                AssetType.CRYPTO, "KRW", Money.of("1", AssetType.CRYPTO, "BTC"), new BigDecimal("0.4")))
                .isInstanceOf(BelowMinimumNotionalException.class);

        assertThat(balanceOf(accountId, "KRW")).isEqualByComparingTo("1000");
        assertThat(balanceOf(accountId, "BTC")).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("최소 단위보다 작은 대금의 매도도 거부된다")
    void rejects_sale_below_minimum_unit() {
        UUID accountId = seed("1000");
        mockRate("0.6");
        giveBtc(accountId, "5");

        // 1 BTC x 0.6 KRW = 0.6 KRW. 예전에는 1 원으로 올림되어 통화가 생성되었다.
        assertThatThrownBy(() -> tradeFacade.sellAsset("dust-" + UUID.randomUUID(), accountId, "BTC",
                AssetType.CRYPTO, "KRW", Money.of("1", AssetType.CRYPTO, "BTC"), new BigDecimal("0.6")))
                .isInstanceOf(BelowMinimumNotionalException.class);

        assertThat(balanceOf(accountId, "KRW")).isEqualByComparingTo("1000");
        assertThat(balanceOf(accountId, "BTC")).isEqualByComparingTo("5");
    }

    @Test
    @DisplayName("매수 대금은 올림(UP)으로 청구되어 고객에게 유리하게 깎이지 않는다")
    void purchase_amount_rounds_up() {
        UUID accountId = seed("1000");
        mockRate("10.4");

        // 1 BTC x 10.4 KRW = 10.4 -> UP 이므로 11 원 청구. HALF_EVEN 이면 10 원이 되어 0.4 원 유실.
        tradeFacade.buyAsset("up-" + UUID.randomUUID(), accountId, "BTC",
                AssetType.CRYPTO, "KRW", Money.of("1", AssetType.CRYPTO, "BTC"), new BigDecimal("10.4"));

        assertThat(balanceOf(accountId, "KRW"))
                .as("1000 - 11 = 989 (올림 청구)")
                .isEqualByComparingTo("989");
    }

    @Test
    @DisplayName("매도 대금은 내림(DOWN)으로 지급되어 통화가 부풀려지지 않는다")
    void sale_amount_rounds_down() {
        UUID accountId = seed("0");
        mockRate("10.6");
        giveBtc(accountId, "5");

        // 1 BTC x 10.6 KRW = 10.6 -> DOWN 이므로 10 원 지급. HALF_EVEN 이면 11 원으로 0.4 원 창출.
        tradeFacade.sellAsset("down-" + UUID.randomUUID(), accountId, "BTC",
                AssetType.CRYPTO, "KRW", Money.of("1", AssetType.CRYPTO, "BTC"), new BigDecimal("10.6"));

        assertThat(balanceOf(accountId, "KRW"))
                .as("내림 지급으로 10 원")
                .isEqualByComparingTo("10");
    }

    @Test
    @DisplayName("Money 는 반올림 방향을 명시할 수 있다")
    void money_supports_explicit_rounding_direction() {
        assertThat(Money.of(new BigDecimal("0.4"), AssetType.FIAT, "KRW", RoundingMode.UP).getAmount())
                .isEqualByComparingTo("1");
        assertThat(Money.of(new BigDecimal("0.6"), AssetType.FIAT, "KRW", RoundingMode.DOWN).getAmount())
                .isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("잔고 부족은 입력 오류가 아닌 전용 예외로 구분된다")
    void insufficient_balance_is_distinct() {
        UUID accountId = seed("5");
        mockRate("1000");

        assertThatThrownBy(() -> tradeFacade.buyAsset("poor-" + UUID.randomUUID(), accountId, "BTC",
                AssetType.CRYPTO, "KRW", Money.of("1", AssetType.CRYPTO, "BTC"), new BigDecimal("1000")))
                .isInstanceOf(InsufficientBalanceException.class);
    }
}
