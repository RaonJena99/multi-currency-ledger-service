package com.github.raonjena99.multi_currency_ledger_service.transaction.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.github.raonjena99.multi_currency_ledger_service.IntegrationTestSupport;
import com.github.raonjena99.multi_currency_ledger_service.account.domain.Account;
import com.github.raonjena99.multi_currency_ledger_service.account.domain.MonthlyAccountLedger;
import com.github.raonjena99.multi_currency_ledger_service.account.infrastructure.AccountRepository;
import com.github.raonjena99.multi_currency_ledger_service.account.infrastructure.MonthlyAccountLedgerRepository;
import com.github.raonjena99.multi_currency_ledger_service.common.domain.Money;
import com.github.raonjena99.multi_currency_ledger_service.common.model.AssetType;
import com.github.raonjena99.multi_currency_ledger_service.common.outbox.OutboxEvent;
import com.github.raonjena99.multi_currency_ledger_service.common.outbox.OutboxRepository;
import com.github.raonjena99.multi_currency_ledger_service.transaction.application.LedgerIntegrityCheckService.IntegrityCheckResult;
import com.github.raonjena99.multi_currency_ledger_service.transaction.application.command.LedgerRecordingCommand;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * 월차 원장 잔고와 분개 누계의 정합성 점검을 실제 DB 로 검증합니다.
 */
@DisplayName("원장 정합성 점검")
class LedgerIntegrityCheckServiceTest extends IntegrationTestSupport {

    private static final String MONTH = OffsetDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("yyyy-MM"));
    private static final String PREVIOUS_MONTH = OffsetDateTime.now(ZoneOffset.UTC).minusMonths(1)
            .format(DateTimeFormatter.ofPattern("yyyy-MM"));

    @Autowired private LedgerIntegrityCheckService checkService;
    @Autowired private LedgerService ledgerService;
    @Autowired private AccountRepository accountRepository;
    @Autowired private MonthlyAccountLedgerRepository ledgerRepository;
    @Autowired private OutboxRepository outboxRepository;
    @Autowired private MeterRegistry meterRegistry;
    @Autowired private JdbcTemplate jdbcTemplate;

    @AfterEach
    void tearDown() {
        jdbcTemplate.execute("TRUNCATE TABLE ledger_integrity_mismatches, ledger_integrity_runs, outbox_events, "
                + "transaction_entries, transactions, monthly_account_ledgers CASCADE");
        deleteTestAccounts();
    }

    private UUID openAccount() {
        UUID accountId = UUID.randomUUID();
        accountRepository.save(Account.open(accountId, "CHECK_USER", "KRW"));
        return accountId;
    }

    /** 원장 행만 만든다. 분개는 따로 기록해야 한다. */
    private void ledger(UUID accountId, String month, String krw) {
        MonthlyAccountLedger row = MonthlyAccountLedger.initialize(accountId, "KRW", AssetType.FIAT, month, "KRW");
        row.addBalance(Money.of(krw, AssetType.FIAT, "KRW"), BigDecimal.ONE);
        ledgerRepository.save(row);
    }

    private void depositEntries(UUID accountId, String krw) {
        ledgerService.recordDoubleEntry(new LedgerRecordingCommand(UUID.randomUUID(), accountId, "KRW", AssetType.FIAT,
                "KRW", "KRW", "DEPOSIT", Money.of(krw, AssetType.FIAT, "KRW"), BigDecimal.ONE, BigDecimal.ONE,
                BigDecimal.ONE, BigDecimal.ZERO, false, OffsetDateTime.now()));
    }

    /** 원장 변경이 정리 대기 시간보다 오래되었다고 만든다. */
    private void settleLedgers() {
        jdbcTemplate.update("UPDATE monthly_account_ledgers SET updated_at = now() - interval '1 hour'");
    }

    private void outbox(UUID accountId, boolean deadLetter) {
        Long id = outboxRepository.save(new OutboxEvent("Ledger", accountId.toString(), "LedgerRecordingCommand",
                "{}", null)).getId();
        if (deadLetter) {
            jdbcTemplate.update("UPDATE outbox_events SET dead_letter = true WHERE id = ?", id);
        } else {
            // 릴레이가 실제로 집어 가지 않도록 다음 시도를 미래로 미룬다. 미처리 상태는 그대로다.
            jdbcTemplate.update("UPDATE outbox_events SET next_attempt_at = now() + interval '1 day' WHERE id = ?", id);
        }
    }

    @Test
    @DisplayName("잔고와 분개 누계가 같으면 불일치가 없다")
    void consistentLedgerHasNoMismatch() {
        UUID accountId = openAccount();
        ledger(accountId, MONTH, "1000");
        depositEntries(accountId, "1000");
        settleLedgers();

        IntegrityCheckResult result = checkService.runCheck();

        assertThat(result.mismatches()).isEmpty();
        assertThat(result.checkedCount()).isEqualTo(1);
        assertThat(result.trialBalanceBalanced()).isTrue();
    }

    @Test
    @DisplayName("분개가 빠진 잔고를 계좌·자산 단위로 검출한다")
    void detectsMissingEntries() {
        UUID accountId = openAccount();
        ledger(accountId, MONTH, "1000");
        depositEntries(accountId, "600");
        settleLedgers();

        IntegrityCheckResult result = checkService.runCheck();

        assertThat(result.mismatches()).singleElement().satisfies(m -> {
            assertThat(m.accountId()).isEqualTo(accountId);
            assertThat(m.assetCode()).isEqualTo("KRW");
            assertThat(m.ledgerBalance()).isEqualByComparingTo("1000");
            assertThat(m.journalBalance()).isEqualByComparingTo("600");
            assertThat(m.difference()).isEqualByComparingTo("400");
            assertThat(m.outboxDeadLetter()).isFalse();
        });
    }

    @Test
    @DisplayName("최근에 바뀐 잔고는 분개가 아직 기록 중일 수 있으므로 건너뛴다")
    void skipsRecentlyChangedLedger() {
        UUID accountId = openAccount();
        ledger(accountId, MONTH, "1000");

        IntegrityCheckResult result = checkService.runCheck();

        assertThat(result.mismatches()).isEmpty();
        assertThat(result.skippedCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("발행 대기 중인 아웃박스 이벤트가 있는 계좌는 건너뛴다")
    void skipsAccountWithPendingOutbox() {
        UUID accountId = openAccount();
        ledger(accountId, MONTH, "1000");
        settleLedgers();
        outbox(accountId, false);

        IntegrityCheckResult result = checkService.runCheck();

        assertThat(result.mismatches()).isEmpty();
        assertThat(result.skippedCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("아웃박스 이벤트가 데드레터로 격리된 계좌는 건너뛰지 않고 불일치로 보고한다")
    void reportsAccountWithDeadLetterOutbox() {
        UUID accountId = openAccount();
        ledger(accountId, MONTH, "1000");
        settleLedgers();
        outbox(accountId, true);

        IntegrityCheckResult result = checkService.runCheck();

        assertThat(result.mismatches()).singleElement()
                .satisfies(m -> assertThat(m.outboxDeadLetter()).isTrue());
    }

    @Test
    @DisplayName("월이 바뀌어 이월된 계좌는 최신 월 잔고 하나만 비교한다")
    void comparesOnlyLatestMonthAfterCarryForward() {
        UUID accountId = openAccount();
        // 이전 월 행 500 은 최신 월 행 1500 으로 이월된 사본이다. 더하면 2000 이 되어 오탐한다.
        ledger(accountId, PREVIOUS_MONTH, "500");
        ledger(accountId, MONTH, "1500");
        depositEntries(accountId, "500");
        depositEntries(accountId, "1000");
        settleLedgers();

        IntegrityCheckResult result = checkService.runCheck();

        assertThat(result.mismatches()).isEmpty();
    }

    @Test
    @DisplayName("시스템 계정은 월차 원장이 없으므로 비교하지 않는다")
    void ignoresSystemAccounts() {
        UUID accountId = openAccount();
        ledger(accountId, MONTH, "1000");
        // 입금 분개는 고객 차변과 청산 계정 대변이다. 청산 계정에는 원장 행이 없다.
        depositEntries(accountId, "1000");
        settleLedgers();

        IntegrityCheckResult result = checkService.runCheck();

        assertThat(result.mismatches()).isEmpty();
    }

    @Test
    @DisplayName("전체 분개의 차대가 맞지 않으면 시산표 불일치로 기록한다")
    void recordsTrialBalanceImbalance() {
        UUID accountId = openAccount();
        UUID broken = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO transactions (id, transaction_type, transacted_at, description) "
                + "VALUES (?, 'BUY', now(), 'broken')", broken);
        jdbcTemplate.update("INSERT INTO transaction_entries (transaction_id, account_id, entry_type, asset_code, "
                + "quantity_asset_type, quantity, quantity_currency, unit_price, amount, amount_asset_type, "
                + "amount_currency, realized_pnl, realized_pnl_asset_type, realized_pnl_currency) "
                + "VALUES (?, ?, 'DEBIT', 'KRW', 'FIAT', 10, 'KRW', 1, 10, 'FIAT', 'KRW', 0, 'FIAT', 'KRW')",
                broken, accountId);

        IntegrityCheckResult result = checkService.runCheck();

        assertThat(result.trialBalanceBalanced()).isFalse();
    }

    @Test
    @DisplayName("점검 결과는 저장되고, 직전 회차의 불일치 건수가 지표로 노출된다")
    void persistsResultAndExposesGauge() {
        UUID accountId = openAccount();
        ledger(accountId, MONTH, "1000");
        settleLedgers();

        IntegrityCheckResult result = checkService.runCheck();

        assertThat(checkService.findLatest()).hasValueSatisfying(latest -> {
            assertThat(latest.runId()).isEqualTo(result.runId());
            assertThat(latest.mismatches()).hasSize(1);
        });
        assertThat(meterRegistry.get("ledger.integrity.mismatches").gauge().value()).isEqualTo(1.0);

        depositEntries(accountId, "1000");
        checkService.runCheck();
        assertThat(meterRegistry.get("ledger.integrity.mismatches").gauge().value()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("보존 기간(90일)이 지난 점검 결과는 불일치 기록과 함께 지운다")
    void purgesRunsOlderThanRetention() {
        UUID accountId = openAccount();
        ledger(accountId, MONTH, "1000");
        settleLedgers();
        IntegrityCheckResult old = checkService.runCheck();
        jdbcTemplate.update("UPDATE ledger_integrity_runs SET checked_at = now() - interval '91 days' WHERE id = ?",
                old.runId());
        IntegrityCheckResult recent = checkService.runCheck();

        int purged = checkService.purgeOldRuns();

        assertThat(purged).isEqualTo(1);
        assertThat(jdbcTemplate.queryForList("SELECT id FROM ledger_integrity_runs", UUID.class))
                .containsExactly(recent.runId());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM ledger_integrity_mismatches WHERE run_id = ?", Integer.class, old.runId()))
                .isZero();
    }

    @Autowired private com.github.raonjena99.multi_currency_ledger_service.transaction.infrastructure.query.LedgerIntegrityDao integrityDao;
    @Autowired private com.github.raonjena99.multi_currency_ledger_service.transaction.infrastructure.query.LedgerQueryDao queryDao;

    @Test
    @DisplayName("재시작해도 지표는 0 이 아니라 직전 회차의 불일치 건수로 시작한다")
    void gaugeStartsFromLatestRunAfterRestart() {
        UUID accountId = openAccount();
        ledger(accountId, MONTH, "1000");
        settleLedgers();
        checkService.runCheck();

        // 배포로 새로 뜬 인스턴스. 다음 점검(최대 하루 뒤)까지 0 으로 보이면 남은 불일치가 알림에서 사라진다.
        var restartedRegistry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        new LedgerIntegrityCheckService(integrityDao, queryDao, restartedRegistry, 10, 90);

        assertThat(restartedRegistry.get("ledger.integrity.mismatches").gauge().value()).isEqualTo(1.0);
    }
}
