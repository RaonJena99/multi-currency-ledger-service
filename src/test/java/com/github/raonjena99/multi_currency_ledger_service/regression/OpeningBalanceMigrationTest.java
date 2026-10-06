package com.github.raonjena99.multi_currency_ledger_service.regression;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.sql.Connection;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import com.github.raonjena99.multi_currency_ledger_service.IntegrationTestSupport;
import com.github.raonjena99.multi_currency_ledger_service.account.domain.Account;
import com.github.raonjena99.multi_currency_ledger_service.account.domain.MonthlyAccountLedger;
import com.github.raonjena99.multi_currency_ledger_service.account.infrastructure.AccountRepository;
import com.github.raonjena99.multi_currency_ledger_service.account.infrastructure.MonthlyAccountLedgerRepository;
import com.github.raonjena99.multi_currency_ledger_service.common.domain.Money;
import com.github.raonjena99.multi_currency_ledger_service.common.model.AssetType;
import com.github.raonjena99.multi_currency_ledger_service.common.outbox.OutboxEvent;
import com.github.raonjena99.multi_currency_ledger_service.common.outbox.OutboxRepository;
import com.github.raonjena99.multi_currency_ledger_service.reconciliation.infrastructure.query.InternalTransactionCandidate;
import com.github.raonjena99.multi_currency_ledger_service.reconciliation.infrastructure.query.InternalTransactionQueryDao;
import com.github.raonjena99.multi_currency_ledger_service.transaction.application.LedgerIntegrityCheckService;
import com.github.raonjena99.multi_currency_ledger_service.transaction.application.LedgerService;
import com.github.raonjena99.multi_currency_ledger_service.transaction.application.command.LedgerRecordingCommand;
import com.github.raonjena99.multi_currency_ledger_service.transaction.domain.LedgerDeadLetter;
import com.github.raonjena99.multi_currency_ledger_service.transaction.infrastructure.LedgerDeadLetterRepository;

/**
 * 입출금 API 이전에 DB 에 직접 넣은 잔고에 기초 잔고 분개를 만드는 마이그레이션을 검증합니다.
 *
 * <p>Flyway 는 빈 DB 에서 이미 이 스크립트를 실행했습니다. 여기서는 분개 없는 잔고를 만든 뒤
 * 같은 스크립트를 다시 실행해, 운영 DB 에 처음 적용될 때와 같은 상황을 재현합니다.
 */
@DisplayName("회귀 테스트: 기초 잔고 분개 마이그레이션")
class OpeningBalanceMigrationTest extends IntegrationTestSupport {

    private static final String SCRIPT = "db/migration/V202610060004__create_opening_balance_entries.sql";
    private static final String MONTH = OffsetDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("yyyy-MM"));
    private static final UUID OPENING_BALANCE_ACCOUNT_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");

    @Autowired private DataSource dataSource;
    @Autowired private AccountRepository accountRepository;
    @Autowired private MonthlyAccountLedgerRepository ledgerRepository;
    @Autowired private LedgerService ledgerService;
    @Autowired private OutboxRepository outboxRepository;
    @Autowired private LedgerDeadLetterRepository ledgerDeadLetterRepository;
    @Autowired private LedgerIntegrityCheckService integrityCheckService;
    @Autowired private InternalTransactionQueryDao candidateQueryDao;
    @Autowired private JdbcTemplate jdbcTemplate;

    /** 분개 없이 KRW 1,000 / BTC 2 (평균 100) / USD 50 (평균 1,300) 을 가진 계좌 */
    private final UUID seeded = UUID.randomUUID();
    /** KRW 1,000 중 600 만 입금 분개가 있는 계좌 → 기초 잔고 400 */
    private final UUID partlyJournaled = UUID.randomUUID();
    /** 수수료 보정으로 KRW -30 이 된 계좌 → 고객 대변 30 */
    private final UUID negative = UUID.randomUUID();
    /** 발행 대기 중인 아웃박스 이벤트가 있는 계좌 → 처리 중이므로 건너뜀 */
    private final UUID inFlight = UUID.randomUUID();
    /** 아웃박스 데드레터가 있는 계좌 → 장애로 빠진 분개를 덮지 않도록 건너뜀 */
    private final UUID outboxFailed = UUID.randomUUID();
    /** 미해결 원장 DLT 가 있는 계좌 → 건너뜀 */
    private final UUID ledgerFailed = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        for (UUID id : List.of(seeded, partlyJournaled, negative, inFlight, outboxFailed, ledgerFailed)) {
            accountRepository.save(Account.open(id, "LEGACY_USER", "KRW"));
            ledger(id, "KRW", AssetType.FIAT, "1000", BigDecimal.ONE);
        }
        ledger(seeded, "BTC", AssetType.CRYPTO, "2", new BigDecimal("100"));
        ledger(seeded, "USD", AssetType.FIAT, "50", new BigDecimal("1300"));
        jdbcTemplate.update("UPDATE monthly_account_ledgers SET balance = -30 WHERE account_id = ?", negative);

        ledgerService.recordDoubleEntry(new LedgerRecordingCommand(UUID.randomUUID(), partlyJournaled, "KRW",
                AssetType.FIAT, "KRW", "KRW", "DEPOSIT", Money.of("600", AssetType.FIAT, "KRW"), BigDecimal.ONE,
                BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO, false, OffsetDateTime.now()));

        outbox(inFlight, false);
        outbox(outboxFailed, true);
        ledgerDeadLetterRepository.save(LedgerDeadLetter.isolate("LedgerRecordingCommand", "boom",
                "{\"accountId\":\"" + ledgerFailed + "\"}", null));

        jdbcTemplate.update("UPDATE monthly_account_ledgers SET updated_at = now() - interval '1 hour'");
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.execute("TRUNCATE TABLE ledger_integrity_mismatches, ledger_integrity_runs, ledger_dead_letters, "
                + "outbox_events, transaction_entries, transactions, monthly_account_ledgers CASCADE");
        deleteTestAccounts();
    }

    private void ledger(UUID accountId, String asset, AssetType type, String balance, BigDecimal averagePrice) {
        MonthlyAccountLedger row = MonthlyAccountLedger.initialize(accountId, asset, type, MONTH, "KRW");
        row.addBalance(Money.of(balance, type, asset), averagePrice);
        ledgerRepository.save(row);
    }

    private void outbox(UUID accountId, boolean deadLetter) {
        Long id = outboxRepository.save(new OutboxEvent("Ledger", accountId.toString(), "LedgerRecordingCommand",
                "{}", null)).getId();
        jdbcTemplate.update("UPDATE outbox_events SET dead_letter = ?, next_attempt_at = now() + interval '1 day' "
                + "WHERE id = ?", deadLetter, id);
    }

    private void runMigration() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource(SCRIPT));
        }
    }

    private List<Map<String, Object>> openingEntries(UUID accountId) {
        return jdbcTemplate.queryForList("SELECT te.entry_type, te.asset_code, te.quantity, te.amount, te.amount_currency "
                + "FROM transaction_entries te JOIN transactions t ON t.id = te.transaction_id "
                + "WHERE t.transaction_type = 'OPENING_BALANCE' AND te.account_id = ? ORDER BY te.asset_code", accountId);
    }

    private int openingTransactionCount() {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM transactions WHERE transaction_type = 'OPENING_BALANCE'", Integer.class);
    }

    @Test
    @DisplayName("분개가 없는 잔고만큼 고객 차변 / 기초 잔고 계정 대변 분개를 만든다")
    void createsOpeningEntriesForUnjournaledBalances() throws Exception {
        runMigration();

        List<Map<String, Object>> entries = openingEntries(seeded);
        assertThat(entries).extracting(e -> e.get("asset_code")).containsExactly("BTC", "KRW", "USD");
        assertThat(entries).allSatisfy(e -> {
            assertThat(e.get("entry_type")).isEqualTo("DEBIT");
            assertThat(e.get("amount_currency")).isEqualTo("KRW");
        });
        // 금액은 평균 단가로 환산한 기준 통화 값: BTC 2 × 100, KRW 1,000 × 1, USD 50 × 1,300
        assertThat((BigDecimal) entries.get(0).get("amount")).isEqualByComparingTo("200");
        assertThat((BigDecimal) entries.get(1).get("amount")).isEqualByComparingTo("1000");
        assertThat((BigDecimal) entries.get(2).get("amount")).isEqualByComparingTo("65000");

        assertThat(openingEntries(OPENING_BALANCE_ACCOUNT_ID))
                .filteredOn(e -> "CREDIT".equals(e.get("entry_type"))).isNotEmpty();
    }

    @Test
    @DisplayName("이미 분개가 있는 부분은 빼고 차이만큼만 만든다")
    void createsOnlyTheDifference() throws Exception {
        runMigration();

        assertThat(openingEntries(partlyJournaled)).singleElement()
                .satisfies(e -> assertThat((BigDecimal) e.get("quantity")).isEqualByComparingTo("400"));
    }

    @Test
    @DisplayName("잔고가 분개 누계보다 작으면 고객 대변으로 만든다")
    void createsCreditForNegativeDifference() throws Exception {
        runMigration();

        assertThat(openingEntries(negative)).singleElement().satisfies(e -> {
            assertThat(e.get("entry_type")).isEqualTo("CREDIT");
            assertThat((BigDecimal) e.get("quantity")).isEqualByComparingTo("30");
        });
    }

    @Test
    @DisplayName("처리 중이거나 장애로 분개가 빠진 계좌는 기초 잔고로 덮지 않는다")
    void skipsAccountsWithInFlightOrFailedEvents() throws Exception {
        runMigration();

        assertThat(openingEntries(inFlight)).isEmpty();
        assertThat(openingEntries(outboxFailed)).isEmpty();
        assertThat(openingEntries(ledgerFailed)).isEmpty();
    }

    @Test
    @DisplayName("적용 후 정합성 점검은 장애 계좌만 불일치로 보고하고, 시산표는 맞는다")
    void integrityCheckPassesAfterMigration() throws Exception {
        runMigration();

        var result = integrityCheckService.runCheck();

        // 처리 중인 계좌는 건너뛰고, 장애로 분개가 빠진 두 계좌만 남는다.
        assertThat(result.mismatches()).extracting(m -> m.accountId())
                .containsExactlyInAnyOrder(outboxFailed, ledgerFailed);
        assertThat(result.trialBalanceBalanced()).isTrue();
    }

    @Test
    @DisplayName("다시 실행해도 분개를 더 만들지 않는다")
    void isIdempotent() throws Exception {
        runMigration();
        int afterFirst = openingTransactionCount();

        runMigration();

        // seeded 3 + partlyJournaled 1 + negative 1
        assertThat(afterFirst).isEqualTo(5);
        assertThat(openingTransactionCount()).isEqualTo(afterFirst);
    }

    @Test
    @DisplayName("기초 잔고 분개는 PG 대사 후보가 아니다")
    void openingBalancesAreNotReconciliationCandidates() throws Exception {
        runMigration();

        List<UUID> openingIds = jdbcTemplate.queryForList(
                "SELECT id FROM transactions WHERE transaction_type = 'OPENING_BALANCE'", UUID.class);
        List<UUID> candidates = candidateQueryDao
                .fetchCandidatesForPeriod(OffsetDateTime.now().minusDays(1), OffsetDateTime.now().plusDays(1))
                .stream().map(InternalTransactionCandidate::transactionId).toList();

        assertThat(candidates).doesNotContainAnyElementsOf(openingIds);
    }
}
