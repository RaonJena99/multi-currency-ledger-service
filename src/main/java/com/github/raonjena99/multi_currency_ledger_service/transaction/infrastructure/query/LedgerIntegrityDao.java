package com.github.raonjena99.multi_currency_ledger_service.transaction.infrastructure.query;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import lombok.RequiredArgsConstructor;

/**
 * 월차 원장 잔고와 분개 누계를 비교하고, 점검 결과를 저장·조회합니다.
 *
 * <p>잔고는 account 모듈의 {@code monthly_account_ledgers} 에 있습니다. 모든 계좌를 계좌 API 로 하나씩
 * 읽으면 계좌 수만큼 조회가 나가므로, 대사 후보 조회({@code InternalTransactionQueryDao})와 같이
 * 한 번의 SQL 로 두 테이블을 맞춰 봅니다.
 */
@Repository
@RequiredArgsConstructor
public class LedgerIntegrityDao {

    /**
     * 시스템 계정. 분개만 있고 월차 원장은 없으므로 비교 대상에서 뺍니다.
     * 소유자 이름(SYSTEM_*)으로 거르면 같은 이름으로 개설된 고객 계좌까지 빠지므로 ID 로 지정합니다.
     */
    private static final List<UUID> SYSTEM_ACCOUNT_IDS = List.of(
            UUID.fromString("00000000-0000-0000-0000-000000000000"),  // SYSTEM_FX (반올림 잔차)
            UUID.fromString("00000000-0000-0000-0000-000000000001"),  // SYSTEM_FEE (수수료)
            UUID.fromString("00000000-0000-0000-0000-000000000002"),  // SYSTEM_CASH_CLEARING (입출금)
            UUID.fromString("00000000-0000-0000-0000-000000000003")); // SYSTEM_OPENING_BALANCE (기초 잔고)

    /**
     * 비교 대상 (계좌, 자산) 쌍.
     *
     * <p>잔고는 최신 월 행만 씁니다. 이전 월 행은 이월된 사본이라 더하면 안 됩니다.
     * 분개 누계는 차변 수량을 더하고 대변 수량을 뺀 값입니다. 잔고나 분개 중 한쪽만 있는 쌍도 포함합니다.
     */
    private static final String PAIRS = """
            WITH latest AS (
                SELECT DISTINCT ON (account_id, asset_code) account_id, asset_code, balance, updated_at
                FROM monthly_account_ledgers
                ORDER BY account_id, asset_code, ledger_month DESC
            ), journal AS (
                SELECT account_id, asset_code,
                       SUM(CASE WHEN entry_type = 'DEBIT' THEN quantity ELSE -quantity END) AS net
                FROM transaction_entries
                WHERE account_id NOT IN (:systemAccountIds)
                GROUP BY account_id, asset_code
            ), pairs AS (
                SELECT COALESCE(l.account_id, j.account_id) AS account_id,
                       COALESCE(l.asset_code, j.asset_code) AS asset_code,
                       COALESCE(l.balance, 0) AS ledger_balance,
                       COALESCE(j.net, 0) AS journal_balance,
                       l.updated_at
                FROM latest l
                FULL JOIN journal j ON j.account_id = l.account_id AND j.asset_code = l.asset_code
            )
            """;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    /**
     * 잔고와 분개 누계가 다른 쌍.
     *
     * @param updatedAt     잔고가 마지막으로 바뀐 시각. 분개만 있고 잔고 행이 없으면 null
     * @param outboxPending 이 계좌로 발행 대기 중인 아웃박스 이벤트가 있음 (분개가 아직 오는 중일 수 있음)
     * @param outboxDeadLetter 이 계좌의 아웃박스 이벤트가 데드레터로 격리됨 (분개가 오지 않음)
     */
    public record DifferenceView(UUID accountId, String assetCode, BigDecimal ledgerBalance,
                                 BigDecimal journalBalance, OffsetDateTime updatedAt,
                                 boolean outboxPending, boolean outboxDeadLetter) {}

    public record RunView(UUID runId, OffsetDateTime checkedAt, int checkedCount, int skippedCount,
                          int mismatchCount, boolean trialBalanceBalanced) {}

    public record MismatchView(UUID accountId, String assetCode, BigDecimal ledgerBalance,
                               BigDecimal journalBalance, BigDecimal difference, boolean outboxDeadLetter) {}

    public int countPairs() {
        Integer count = jdbcTemplate.queryForObject(PAIRS + "SELECT count(*) FROM pairs", systemAccountParams(),
                Integer.class);
        return count != null ? count : 0;
    }

    public List<DifferenceView> findDifferences() {
        // 아웃박스 확인은 잔고와 분개가 다른 쌍에만 하도록 차이를 먼저 거른다.
        String sql = PAIRS + """
                SELECT p.account_id, p.asset_code, p.ledger_balance, p.journal_balance, p.updated_at,
                       EXISTS (SELECT 1 FROM outbox_events o
                               WHERE o.aggregate_id = p.account_id::text
                                 AND o.processed = false AND o.dead_letter = false) AS outbox_pending,
                       EXISTS (SELECT 1 FROM outbox_events o
                               WHERE o.aggregate_id = p.account_id::text AND o.dead_letter = true) AS outbox_dead_letter
                FROM pairs p
                WHERE p.ledger_balance <> p.journal_balance
                ORDER BY p.account_id, p.asset_code
                """;
        return jdbcTemplate.query(sql, systemAccountParams(), (rs, rowNum) -> new DifferenceView(
                rs.getObject("account_id", UUID.class),
                rs.getString("asset_code"),
                rs.getBigDecimal("ledger_balance"),
                rs.getBigDecimal("journal_balance"),
                rs.getObject("updated_at", OffsetDateTime.class),
                rs.getBoolean("outbox_pending"),
                rs.getBoolean("outbox_dead_letter")));
    }

    public void saveRun(RunView run, List<MismatchView> mismatches) {
        jdbcTemplate.update("""
                INSERT INTO ledger_integrity_runs
                    (id, checked_at, checked_count, skipped_count, mismatch_count, trial_balance_balanced)
                VALUES (:id, :checkedAt, :checkedCount, :skippedCount, :mismatchCount, :balanced)
                """, new MapSqlParameterSource("id", run.runId())
                .addValue("checkedAt", run.checkedAt())
                .addValue("checkedCount", run.checkedCount())
                .addValue("skippedCount", run.skippedCount())
                .addValue("mismatchCount", run.mismatchCount())
                .addValue("balanced", run.trialBalanceBalanced()));

        MapSqlParameterSource[] rows = mismatches.stream().map(m -> new MapSqlParameterSource("runId", run.runId())
                .addValue("accountId", m.accountId())
                .addValue("assetCode", m.assetCode())
                .addValue("ledgerBalance", m.ledgerBalance())
                .addValue("journalBalance", m.journalBalance())
                .addValue("difference", m.difference())
                .addValue("deadLetter", m.outboxDeadLetter()))
                .toArray(MapSqlParameterSource[]::new);
        jdbcTemplate.batchUpdate("""
                INSERT INTO ledger_integrity_mismatches
                    (run_id, account_id, asset_code, ledger_balance, journal_balance, difference, outbox_dead_letter)
                VALUES (:runId, :accountId, :assetCode, :ledgerBalance, :journalBalance, :difference, :deadLetter)
                """, rows);
    }

    public Optional<RunView> findLatestRun() {
        return jdbcTemplate.query("""
                SELECT id, checked_at, checked_count, skipped_count, mismatch_count, trial_balance_balanced
                FROM ledger_integrity_runs
                ORDER BY checked_at DESC
                LIMIT 1
                """, new MapSqlParameterSource(), (rs, rowNum) -> new RunView(
                rs.getObject("id", UUID.class),
                rs.getObject("checked_at", OffsetDateTime.class),
                rs.getInt("checked_count"),
                rs.getInt("skipped_count"),
                rs.getInt("mismatch_count"),
                rs.getBoolean("trial_balance_balanced")))
                .stream().findFirst();
    }

    public List<MismatchView> findMismatches(UUID runId) {
        return jdbcTemplate.query("""
                SELECT account_id, asset_code, ledger_balance, journal_balance, difference, outbox_dead_letter
                FROM ledger_integrity_mismatches
                WHERE run_id = :runId
                ORDER BY id
                """, new MapSqlParameterSource("runId", runId), (rs, rowNum) -> new MismatchView(
                rs.getObject("account_id", UUID.class),
                rs.getString("asset_code"),
                rs.getBigDecimal("ledger_balance"),
                rs.getBigDecimal("journal_balance"),
                rs.getBigDecimal("difference"),
                rs.getBoolean("outbox_dead_letter")));
    }

    private static MapSqlParameterSource systemAccountParams() {
        return new MapSqlParameterSource("systemAccountIds", SYSTEM_ACCOUNT_IDS);
    }
}
