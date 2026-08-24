package com.github.raonjena99.multi_currency_ledger_service.regression;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
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
import com.github.raonjena99.multi_currency_ledger_service.common.domain.Money;
import com.github.raonjena99.multi_currency_ledger_service.common.model.AssetType;
import com.github.raonjena99.multi_currency_ledger_service.common.outbox.OutboxRelayWorker;
import com.github.raonjena99.multi_currency_ledger_service.common.outbox.OutboxRepository;
import com.github.raonjena99.multi_currency_ledger_service.common.port.ExchangeRateProvider;
import com.github.raonjena99.multi_currency_ledger_service.common.telemetry.CorrelationIdFilter;

/**
 * 잔고와 원장이 같은 환율·같은 시각으로 기록되는지 검증합니다.
 *
 * <p>원장 기록 단계에서 환율을 재조회하면 (1) 잔고에 적용된 환율과 원장 환율이 달라지고
 * (2) Kafka 재시도마다 값이 바뀌며 (3) 트랜잭션 안에서 외부 HTTP 를 호출해 DB 커넥션을 붙잡습니다.
 */
@DisplayName("회귀 테스트: 잔고와 원장의 환율·시각 일관성")
class LedgerConsistencyTest extends IntegrationTestSupport {

    private static final String MONTH = OffsetDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM"));

    @Autowired private AccountTradeFacade tradeFacade;
    @Autowired private AccountRepository accountRepository;
    @Autowired private MonthlyAccountLedgerRepository ledgerRepository;
    @Autowired private OutboxRepository outboxRepository;
    @Autowired private OutboxRelayWorker relayWorker;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TransactionTemplate txTemplate;

    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private ExchangeRateProvider exchangeRateProvider;

    @AfterEach
    void tearDown() {
        org.slf4j.MDC.clear();
        jdbcTemplate.execute("TRUNCATE TABLE outbox_events, transaction_entries, transactions, "
                + "monthly_account_ledgers, idempotency_records CASCADE");
        deleteTestAccounts();
    }

    /** 결제 통화 USD, 기준 통화 KRW 계좌를 만든다. */
    private UUID seedUsdPayingKrwAccount() {
        UUID accountId = UUID.randomUUID();
        txTemplate.execute(status -> {
            accountRepository.save(Account.open(accountId, "FX_USER", "KRW"));
            MonthlyAccountLedger usd = MonthlyAccountLedger.initialize(accountId, "USD", AssetType.FIAT, MONTH, "KRW");
            usd.addBalance(Money.of("100000", AssetType.FIAT, "USD"), BigDecimal.ONE);
            ledgerRepository.save(usd);
            ledgerRepository.save(MonthlyAccountLedger.initialize(accountId, "AAPL", AssetType.STOCK, MONTH, "KRW"));
            return null;
        });
        return accountId;
    }

    @Test
    @DisplayName("거래 시점 환율이 원장에 그대로 기록되고, 소비 시점에 환율이 바뀌어도 영향받지 않는다")
    void ledger_uses_trade_time_rate_even_if_market_moves() {
        UUID accountId = seedUsdPayingKrwAccount();

        BigDecimal tradeTimeFxRate = new BigDecimal("1300");
        // AAPL -> USD 시세 200, USD -> KRW 환율 1300
        org.mockito.Mockito.when(exchangeRateProvider.getExchangeRate("AAPL", "USD"))
                .thenReturn(new ExchangeRateProvider.ExchangeRate(new BigDecimal("200"), false));
        org.mockito.Mockito.when(exchangeRateProvider.getExchangeRate("USD", "KRW"))
                .thenReturn(new ExchangeRateProvider.ExchangeRate(tradeTimeFxRate, false));

        UUID tradeId = tradeFacade.buyAsset("fx-" + UUID.randomUUID(), accountId, "AAPL",
                AssetType.STOCK, "USD", Money.of("2", AssetType.STOCK, "AAPL"), new BigDecimal("200"));

        // 소비 전에 시장이 크게 움직인다. 원장은 이 값을 쓰면 안 된다.
        org.mockito.Mockito.when(exchangeRateProvider.getExchangeRate("USD", "KRW"))
                .thenReturn(new ExchangeRateProvider.ExchangeRate(new BigDecimal("9999"), false));

        relayWorker.relayOutboxEvents();

        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(500)).untilAsserted(() ->
                assertThat(jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM transactions WHERE id = ?", Integer.class, tradeId)).isEqualTo(1));

        List<Map<String, Object>> entries = jdbcTemplate.queryForList(
                "SELECT exchange_rate, amount, amount_currency FROM transaction_entries WHERE transaction_id = ?", tradeId);

        assertThat(entries).isNotEmpty();
        assertThat(entries)
                .as("원장은 거래 시점 환율(1300)을 사용해야 한다")
                .allSatisfy(e -> assertThat((BigDecimal) e.get("exchange_rate")).isEqualByComparingTo(tradeTimeFxRate));

        // 2주 x 200 USD x 1300 = 520000 KRW
        BigDecimal debit = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(amount),0) FROM transaction_entries WHERE transaction_id = ? AND entry_type = 'DEBIT'",
                BigDecimal.class, tradeId);
        assertThat(debit).isEqualByComparingTo("520000");
    }

    @Test
    @DisplayName("원장의 거래 시각은 Kafka 소비 시각이 아니라 실제 거래 시각이다")
    void ledger_transacted_at_matches_trade_time() {
        UUID accountId = seedUsdPayingKrwAccount();
        org.mockito.Mockito.when(exchangeRateProvider.getExchangeRate(org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(new ExchangeRateProvider.ExchangeRate(new BigDecimal("200"), false));

        OffsetDateTime before = OffsetDateTime.now().minusSeconds(1);
        UUID tradeId = tradeFacade.buyAsset("ts-" + UUID.randomUUID(), accountId, "AAPL",
                AssetType.STOCK, "USD", Money.of("1", AssetType.STOCK, "AAPL"), new BigDecimal("200"));
        OffsetDateTime after = OffsetDateTime.now().plusSeconds(1);

        relayWorker.relayOutboxEvents();

        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(500)).untilAsserted(() ->
                assertThat(jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM transactions WHERE id = ?", Integer.class, tradeId)).isEqualTo(1));

        OffsetDateTime recorded = jdbcTemplate.queryForObject(
                "SELECT transacted_at FROM transactions WHERE id = ?", OffsetDateTime.class, tradeId);

        assertThat(recorded).isAfterOrEqualTo(before).isBeforeOrEqualTo(after);
    }

    @Test
    @DisplayName("correlation id 가 아웃박스 행과 Kafka 헤더를 거쳐 끊기지 않고 전달된다")
    void correlation_id_survives_the_outbox_and_kafka_hop() {
        UUID accountId = seedUsdPayingKrwAccount();
        org.mockito.Mockito.when(exchangeRateProvider.getExchangeRate(org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(new ExchangeRateProvider.ExchangeRate(new BigDecimal("200"), false));

        String correlationId = "corr-" + UUID.randomUUID();
        org.slf4j.MDC.put(CorrelationIdFilter.MDC_KEY, correlationId);

        UUID tradeId;
        try {
            tradeId = tradeFacade.buyAsset("corr-" + UUID.randomUUID(), accountId, "AAPL",
                    AssetType.STOCK, "USD", Money.of("1", AssetType.STOCK, "AAPL"), new BigDecimal("200"));
        } finally {
            org.slf4j.MDC.remove(CorrelationIdFilter.MDC_KEY);
        }

        // 아웃박스 행에 correlation id 가 실제로 남아야 한다.
        // MDC 키가 어긋나 있으면 이 값이 조용히 null 이 되어 추적 체인이 끊긴다.
        assertThat(outboxRepository.findAll())
                .isNotEmpty()
                .allSatisfy(e -> assertThat(e.getCorrelationId()).isEqualTo(correlationId));

        relayWorker.relayOutboxEvents();

        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(500)).untilAsserted(() ->
                assertThat(jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM transactions WHERE id = ?", Integer.class, tradeId)).isEqualTo(1));
    }
}
