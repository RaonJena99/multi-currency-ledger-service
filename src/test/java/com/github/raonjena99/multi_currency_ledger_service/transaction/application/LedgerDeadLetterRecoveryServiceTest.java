package com.github.raonjena99.multi_currency_ledger_service.transaction.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import com.github.raonjena99.multi_currency_ledger_service.IntegrationTestSupport;
import com.github.raonjena99.multi_currency_ledger_service.account.domain.Account;
import com.github.raonjena99.multi_currency_ledger_service.account.domain.MonthlyAccountLedger;
import com.github.raonjena99.multi_currency_ledger_service.account.infrastructure.AccountRepository;
import com.github.raonjena99.multi_currency_ledger_service.account.infrastructure.MonthlyAccountLedgerRepository;
import com.github.raonjena99.multi_currency_ledger_service.common.domain.Money;
import com.github.raonjena99.multi_currency_ledger_service.common.exception.LedgerDeadLetterNotFoundException;
import com.github.raonjena99.multi_currency_ledger_service.common.exception.LedgerReplayFailedException;
import com.github.raonjena99.multi_currency_ledger_service.common.model.AssetType;
import com.github.raonjena99.multi_currency_ledger_service.transaction.domain.LedgerDeadLetter;
import com.github.raonjena99.multi_currency_ledger_service.transaction.infrastructure.LedgerDeadLetterRepository;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * 원장 DLT 격리 건의 재처리·해결을 실제 DB 와 실제 {@link LedgerService} 로 검증합니다.
 *
 * <p>페이로드는 발행 측(AccountOutboxAcl, ReconciliationToLedgerAcl)이 아웃박스에 남기는 JSON 과
 * 같은 모양의 리터럴입니다. 역직렬화 경로를 코드로 다시 만들어 넣으면 발행 형식이 바뀌어도
 * 테스트가 함께 따라가 결함을 숨깁니다.
 */
@DisplayName("원장 DLT 재처리")
class LedgerDeadLetterRecoveryServiceTest extends IntegrationTestSupport {

    private static final String TOPIC = "LedgerRecordingCommand";
    private static final OffsetDateTime NOW = OffsetDateTime.now(ZoneOffset.UTC);
    private static final String MONTH = NOW.format(DateTimeFormatter.ofPattern("yyyy-MM"));

    @Autowired private LedgerDeadLetterRecoveryService recoveryService;
    @Autowired private LedgerDeadLetterMetrics deadLetterMetrics;
    @Autowired private LedgerDeadLetterRepository deadLetterRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private MonthlyAccountLedgerRepository ledgerRepository;
    @Autowired private MeterRegistry meterRegistry;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TransactionTemplate txTemplate;

    private UUID accountId;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("TRUNCATE TABLE ledger_dead_letters");
        accountId = UUID.randomUUID();
        txTemplate.execute(status -> {
            accountRepository.save(Account.open(accountId, "DLT_USER", "KRW"));
            MonthlyAccountLedger krw = MonthlyAccountLedger.initialize(accountId, "KRW", AssetType.FIAT, MONTH, "KRW");
            krw.addBalance(Money.of("1000", AssetType.FIAT, "KRW"), BigDecimal.ONE);
            ledgerRepository.save(krw);
            return null;
        });
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.execute("TRUNCATE TABLE ledger_dead_letters, outbox_events, transaction_entries, transactions, "
                + "monthly_account_ledgers CASCADE");
        deleteTestAccounts();
    }

    /** AccountOutboxAcl 이 남기는 매수 페이로드: 2 BTC × 100 KRW. */
    private String buyPayload(UUID tradeId) {
        return """
                {"tradeId":"%s","accountId":"%s","targetAssetCode":"BTC","targetAssetType":"CRYPTO",
                 "paymentCurrency":"KRW","baseCurrency":"KRW","tradeType":"BUY",
                 "quantity":{"amount":2,"assetType":"CRYPTO","currencyCode":"BTC"},
                 "unitPrice":100,"exchangeRate":100,"fiatToBaseRate":1,"averageCost":null,
                 "isStaleRate":false,"transactedAt":"%s"}
                """.formatted(tradeId, accountId, NOW);
    }

    /** ReconciliationToLedgerAcl 이 남기는 수수료 보정 페이로드: 500 KRW 환불. */
    private String feeAdjustmentPayload(UUID settlementId) {
        return """
                {"settlementId":"%s","accountId":"%s","targetAssetCode":"KRW","paymentCurrency":"KRW",
                 "baseCurrency":"KRW","tradeType":"FEE_ADJUSTMENT",
                 "quantity":{"amount":500,"assetType":"FIAT","currencyCode":"KRW"},
                 "unitPrice":1,"exchangeRate":1,"fiatToBaseRate":1,"averageCost":0,
                 "isStaleRate":false,"transactedAt":"%s"}
                """.formatted(settlementId, accountId, NOW);
    }

    private Long isolate(String payload) {
        return deadLetterRepository.save(LedgerDeadLetter.isolate(TOPIC, "boom", payload, "corr-1")).getId();
    }

    private boolean isResolved(Long id) {
        return deadLetterRepository.findById(id).orElseThrow().isResolved();
    }

    private BigDecimal sumAmount(UUID transactionId, String entryType) {
        return jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(amount), 0) FROM transaction_entries WHERE transaction_id = ? AND entry_type = ?",
                BigDecimal.class, transactionId, entryType);
    }

    private int entryCount(UUID transactionId) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM transaction_entries WHERE transaction_id = ?", Integer.class, transactionId);
    }

    @Test
    @DisplayName("매수 데드레터를 재처리하면 분개가 기록되고 해결 상태가 된다")
    void replayBuyRecordsLedgerAndResolves() {
        UUID tradeId = UUID.randomUUID();
        Long id = isolate(buyPayload(tradeId));

        UUID recorded = recoveryService.replay(id);

        assertThat(recorded).isEqualTo(tradeId);
        assertThat(sumAmount(tradeId, "DEBIT")).isEqualByComparingTo("200");
        assertThat(sumAmount(tradeId, "CREDIT")).isEqualByComparingTo("200");
        assertThat(isResolved(id)).isTrue();
    }

    @Test
    @DisplayName("수수료 보정 데드레터를 재처리하면 분개와 함께 잔고 보정도 반영된다")
    void replayFeeAdjustmentAlsoAdjustsBalance() {
        UUID settlementId = UUID.randomUUID();
        Long id = isolate(feeAdjustmentPayload(settlementId));

        recoveryService.replay(id);

        assertThat(entryCount(settlementId)).isEqualTo(2);
        BigDecimal krwBalance = ledgerRepository
                .findByAccountIdAndAssetCodeAndLedgerMonth(accountId, "KRW", MONTH).orElseThrow()
                .getBalance().getAmount();
        assertThat(krwBalance).isEqualByComparingTo("1500");
        assertThat(isResolved(id)).isTrue();
    }

    @Test
    @DisplayName("이미 분개된 거래를 재처리하면 중복 기록 없이 해결 처리만 한다")
    void replayOfAlreadyRecordedTradeDoesNotDuplicate() {
        UUID tradeId = UUID.randomUUID();
        recoveryService.replay(isolate(buyPayload(tradeId)));
        int entriesAfterFirst = entryCount(tradeId);

        Long duplicate = isolate(buyPayload(tradeId));
        recoveryService.replay(duplicate);

        assertThat(entryCount(tradeId)).isEqualTo(entriesAfterFirst);
        assertThat(isResolved(duplicate)).isTrue();
    }

    @Test
    @DisplayName("페이로드를 읽을 수 없으면 실패 사유를 알리고 해결 처리하지 않는다")
    void replayWithBrokenPayloadKeepsDeadLetterUnresolved() {
        Long id = isolate("{not-json");

        assertThatThrownBy(() -> recoveryService.replay(id))
                .isInstanceOf(LedgerReplayFailedException.class);
        assertThat(isResolved(id)).isFalse();
    }

    @Test
    @DisplayName("이미 해결된 데드레터는 재처리하지 않는다")
    void replayOfResolvedDeadLetterIsRejected() {
        Long id = isolate(buyPayload(UUID.randomUUID()));
        recoveryService.resolve(id);

        assertThatThrownBy(() -> recoveryService.replay(id))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("없는 데드레터 ID 는 전용 예외로 알린다")
    void unknownDeadLetterIsReportedAsNotFound() {
        assertThatThrownBy(() -> recoveryService.replay(999_999L))
                .isInstanceOf(LedgerDeadLetterNotFoundException.class);
        assertThatThrownBy(() -> recoveryService.resolve(999_999L))
                .isInstanceOf(LedgerDeadLetterNotFoundException.class);
    }

    @Test
    @DisplayName("재처리 없이 해결 처리만 하면 분개는 만들어지지 않는다")
    void resolveWithoutReplayRecordsNothing() {
        UUID tradeId = UUID.randomUUID();
        Long id = isolate(buyPayload(tradeId));

        recoveryService.resolve(id);

        assertThat(isResolved(id)).isTrue();
        assertThat(entryCount(tradeId)).isZero();
    }

    @Test
    @DisplayName("두 운영자가 같은 데드레터를 동시에 처리하면 나중에 커밋한 쪽은 충돌로 실패한다")
    void concurrentResolutionIsDetected() {
        Long id = isolate(buyPayload(UUID.randomUUID()));
        // 두 운영자가 같은 시점의 상태를 각각 읽는다.
        LedgerDeadLetter first = deadLetterRepository.findById(id).orElseThrow();
        LedgerDeadLetter second = deadLetterRepository.findById(id).orElseThrow();

        first.markAsResolved();
        deadLetterRepository.save(first);

        second.markAsResolved();
        assertThatThrownBy(() -> deadLetterRepository.save(second))
                .isInstanceOf(OptimisticLockingFailureException.class);
    }

    @Test
    @DisplayName("미해결 건수 지표는 해결하면 줄어든다")
    void unresolvedGaugeFollowsResolution() {
        Long id = isolate(buyPayload(UUID.randomUUID()));
        isolate(buyPayload(UUID.randomUUID()));

        deadLetterMetrics.refresh();
        assertThat(meterRegistry.get("ledger.dead_letter.unresolved").gauge().value()).isEqualTo(2.0);

        recoveryService.resolve(id);
        deadLetterMetrics.refresh();
        assertThat(meterRegistry.get("ledger.dead_letter.unresolved").gauge().value()).isEqualTo(1.0);
    }
}
