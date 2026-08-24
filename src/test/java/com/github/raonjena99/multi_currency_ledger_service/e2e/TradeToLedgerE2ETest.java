package com.github.raonjena99.multi_currency_ledger_service.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
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

/**
 * 이 서비스의 주 경로를 실제 직렬화와 실제 인프라로 끝까지 통과시키는 E2E 테스트입니다.
 *
 * 매수 요청 → 월차 원장 잔고 변경 → 아웃박스 적재(실제 JsonMapper 직렬화)
 *   → OutboxRelayWorker → 실제 Kafka → @KafkaListener → LedgerService
 *   → transactions / transaction_entries 영속화(실제 DB 제약 통과)
 *
 * 어느 단계도 mock 으로 대체하지 않습니다. 특히 마지막 DB 영속화 단계는
 * chk_amount_calculation 같은 스키마 제약을 실제로 밟습니다.
 */
@DisplayName("E2E: 매수 → 아웃박스 → Kafka → 복식부기 원장 기록")
class TradeToLedgerE2ETest extends IntegrationTestSupport {

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
        jdbcTemplate.execute("TRUNCATE TABLE outbox_events, transaction_entries, transactions, "
                + "monthly_account_ledgers, idempotency_records CASCADE");
        deleteTestAccounts();
    }

    private UUID seedAccount(String baseCurrency, String fiatCode, String fiatBalance, String assetCode, AssetType assetType) {
        UUID accountId = UUID.randomUUID();
        txTemplate.execute(status -> {
            accountRepository.save(Account.open(accountId, "E2E_USER", baseCurrency));

            MonthlyAccountLedger fiat = MonthlyAccountLedger.initialize(accountId, fiatCode, AssetType.FIAT, MONTH, baseCurrency);
            fiat.addBalance(Money.of(fiatBalance, AssetType.FIAT, fiatCode), BigDecimal.ONE);
            ledgerRepository.save(fiat);

            ledgerRepository.save(MonthlyAccountLedger.initialize(accountId, assetCode, assetType, MONTH, baseCurrency));
            return null;
        });
        return accountId;
    }

    @Test
    @DisplayName("반올림이 발생하는 매수도 원장까지 완주하고, 차변과 대변이 기준 통화로 일치한다")
    void buy_with_rounding_reaches_ledger_and_balances() {
        // given : KRW(스케일 0) 기준 계좌. 단가 100.4 는 기준 통화 스케일에서 반올림을 강제한다.
        UUID accountId = seedAccount("KRW", "KRW", "1000000", "BTC", AssetType.CRYPTO);

        // 단가 검증(시장 시세 대비 허용 편차)을 통과하도록 시세를 현실적으로 맞춘다.
        // 결제 통화와 기준 통화가 같으므로 fiatToBaseRate 는 조회되지 않는다.
        org.mockito.Mockito.when(exchangeRateProvider.getExchangeRate("BTC", "KRW"))
                .thenReturn(new ExchangeRateProvider.ExchangeRate(new BigDecimal("100"), false));

        String idempotencyKey = "e2e-" + UUID.randomUUID();

        // when : 실제 서비스 경로로 매수
        UUID tradeId = tradeFacade.buyAsset(idempotencyKey, accountId, "BTC", AssetType.CRYPTO, "KRW",
                Money.of("2", AssetType.CRYPTO, "BTC"), new BigDecimal("100.4"));

        // then 1 : 잔고가 즉시 반영된다 (동기 트랜잭션)
        MonthlyAccountLedger btc = ledgerRepository
                .findByAccountIdAndAssetCodeAndLedgerMonth(accountId, "BTC", MONTH).orElseThrow();
        assertThat(btc.getBalance().getAmount()).isEqualByComparingTo("2");

        // then 2 : 아웃박스에 실제 JsonMapper 로 직렬화된 행이 남는다.
        // 전체 건수 대신 이 거래의 행만 골라 검증한다. 전체 건수에 의존하면 백그라운드 스케줄러나
        // 다른 테스트가 남긴 행 때문에 테스트가 흔들린다.
        var thisTradeEvents = outboxRepository.findAll().stream()
                .filter(e -> e.getPayload().contains(tradeId.toString()))
                .toList();
        assertThat(thisTradeEvents).hasSize(1);
        assertThat(thisTradeEvents.get(0).getEventType()).isEqualTo("LedgerRecordingCommand");
        assertThat(thisTradeEvents.get(0).getPayload())
                .contains("\"tradeId\":\"" + tradeId + "\"")
                .contains("\"quantity\"")
                .contains("\"fiatToBaseRate\"")
                .contains("\"transactedAt\"");

        // when : 릴레이 워커가 Kafka 로 발행하고, 실제 컨슈머가 원장을 기록한다
        relayWorker.relayOutboxEvents();

        // then 3 : transactions / transaction_entries 가 실제 DB 제약을 통과해 저장된다
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(500)).untilAsserted(() -> {
            Integer txCount = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM transactions WHERE id = ?", Integer.class, tradeId);
            assertThat(txCount)
                    .as("복식부기 원장이 기록되어야 한다 (Kafka 컨슈머 → LedgerService → DB)")
                    .isEqualTo(1);
        });

        // then 4 : 기준 통화 기준으로 차변 == 대변 (실현손익 포함)
        List<java.util.Map<String, Object>> entries = jdbcTemplate.queryForList(
                "SELECT entry_type, amount, realized_pnl, amount_currency, asset_code "
                        + "FROM transaction_entries WHERE transaction_id = ?", tradeId);
        assertThat(entries).as("차변/대변 최소 2건").hasSizeGreaterThanOrEqualTo(2);
        assertThat(entries).allSatisfy(e ->
                assertThat(e.get("amount_currency")).isEqualTo("KRW"));

        BigDecimal debit = sum(entries, "DEBIT");
        BigDecimal credit = sum(entries, "CREDIT").add(pnl(entries));
        assertThat(debit)
                .as("기준 통화 KRW 기준 차변 합계와 대변 합계(실현손익 포함)가 일치해야 한다")
                .isEqualByComparingTo(credit);

        // then 5 : 이 거래의 아웃박스 행이 처리 완료로 마킹된다
        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(500)).untilAsserted(() ->
                assertThat(outboxRepository.findAll().stream()
                        .filter(e -> e.getPayload().contains(tradeId.toString()))
                        .toList())
                        .isNotEmpty()
                        .allSatisfy(e -> assertThat(e.isProcessed()).isTrue()));
    }

    private BigDecimal sum(List<java.util.Map<String, Object>> rows, String type) {
        return rows.stream()
                .filter(r -> type.equals(r.get("entry_type")))
                .map(r -> (BigDecimal) r.get("amount"))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal pnl(List<java.util.Map<String, Object>> rows) {
        return rows.stream()
                .map(r -> (BigDecimal) r.get("realized_pnl"))
                .filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
