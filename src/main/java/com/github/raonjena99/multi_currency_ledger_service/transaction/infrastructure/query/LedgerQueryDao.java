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
 * 복식부기 원장을 읽기 전용으로 조회합니다.
 *
 * <p>JPA 엔티티를 거치지 않고 필요한 컬럼만 읽습니다. 거래 내역은 계좌 단위로 페이지를 나눠야 하고,
 * 시산표는 대량 집계라서 엔티티를 로딩하면 불필요한 객체와 쿼리가 생깁니다.
 */
@Repository
@RequiredArgsConstructor
public class LedgerQueryDao {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public record TransactionSummaryView(UUID transactionId, String transactionType, OffsetDateTime transactedAt) {}

    public record EntryView(String entryType, String assetCode, BigDecimal quantity, BigDecimal unitPrice,
                            BigDecimal exchangeRate, BigDecimal amount, String amountCurrency,
                            BigDecimal realizedPnl) {}

    public record CurrencyTotalsView(String currency, BigDecimal debitTotal, BigDecimal creditTotal,
                                     BigDecimal realizedPnlTotal) {}

    /**
     * 계좌의 분개가 들어 있는 거래를 최신순으로 조회합니다.
     *
     * <p>계좌 분개(transaction_entries.account_id 인덱스)에서 출발해 거래를 찾습니다. 거래 테이블을 시간순으로
     * 훑으며 거래마다 계좌 분개가 있는지 확인하면, 거래가 드문 계좌일수록 전체 거래를 읽게 됩니다.
     *
     * @param from 이 시각 이상 (null 이면 제한 없음)
     * @param to   이 시각 미만 (null 이면 제한 없음)
     */
    public List<TransactionSummaryView> findAccountTransactions(UUID accountId, OffsetDateTime from,
                                                                OffsetDateTime to, int offset, int limit) {
        MapSqlParameterSource params = periodParams(accountId, from, to)
                .addValue("offset", offset)
                .addValue("limit", limit);
        String sql = "SELECT t.id, t.transaction_type, t.transacted_at " + accountTransactionsFrom(from, to)
                + " ORDER BY t.transacted_at DESC, t.id DESC LIMIT :limit OFFSET :offset";
        return jdbcTemplate.query(sql, params, (rs, rowNum) -> new TransactionSummaryView(
                rs.getObject("id", UUID.class),
                rs.getString("transaction_type"),
                rs.getObject("transacted_at", OffsetDateTime.class)));
    }

    public long countAccountTransactions(UUID accountId, OffsetDateTime from, OffsetDateTime to) {
        Long count = jdbcTemplate.queryForObject("SELECT count(*) " + accountTransactionsFrom(from, to),
                periodParams(accountId, from, to), Long.class);
        return count != null ? count : 0L;
    }

    /**
     * 계좌의 분개가 들어 있는 거래 하나를 조회합니다. 그 계좌의 분개가 없으면 비어 있습니다.
     */
    public Optional<TransactionSummaryView> findAccountTransaction(UUID accountId, UUID transactionId) {
        String sql = """
                SELECT t.id, t.transaction_type, t.transacted_at
                FROM transactions t
                WHERE t.id = :transactionId
                  AND EXISTS (SELECT 1 FROM transaction_entries te
                              WHERE te.transaction_id = t.id AND te.account_id = :accountId)
                """;
        return jdbcTemplate.query(sql,
                new MapSqlParameterSource("transactionId", transactionId).addValue("accountId", accountId),
                (rs, rowNum) -> new TransactionSummaryView(
                        rs.getObject("id", UUID.class),
                        rs.getString("transaction_type"),
                        rs.getObject("transacted_at", OffsetDateTime.class)))
                .stream().findFirst();
    }

    /**
     * 거래 하나에서 지정한 계좌의 분개만 조회합니다.
     */
    public List<EntryView> findEntries(UUID transactionId, UUID accountId) {
        String sql = """
                SELECT entry_type, asset_code, quantity, unit_price, exchange_rate,
                       amount, amount_currency, realized_pnl
                FROM transaction_entries
                WHERE transaction_id = :transactionId AND account_id = :accountId
                ORDER BY id
                """;
        return jdbcTemplate.query(sql,
                new MapSqlParameterSource("transactionId", transactionId).addValue("accountId", accountId),
                (rs, rowNum) -> new EntryView(
                        rs.getString("entry_type"),
                        rs.getString("asset_code"),
                        rs.getBigDecimal("quantity"),
                        rs.getBigDecimal("unit_price"),
                        rs.getBigDecimal("exchange_rate"),
                        rs.getBigDecimal("amount"),
                        rs.getString("amount_currency"),
                        rs.getBigDecimal("realized_pnl")));
    }

    /**
     * 기간 안의 모든 분개(시스템 계정 포함)를 기준 통화별로 집계합니다.
     *
     * <p>{@code amount} 는 계좌의 기준 통화로 환산한 금액이라 통화별로 더할 수 있습니다.
     * {@code quantity} 는 자산마다 단위가 달라 합산하지 않습니다.
     */
    public List<CurrencyTotalsView> sumByCurrency(OffsetDateTime start, OffsetDateTime end) {
        String sql = """
                SELECT te.amount_currency AS currency,
                       SUM(CASE WHEN te.entry_type = 'DEBIT' THEN te.amount ELSE 0 END) AS debit_total,
                       SUM(CASE WHEN te.entry_type = 'CREDIT' THEN te.amount ELSE 0 END) AS credit_total,
                       SUM(COALESCE(te.realized_pnl, 0)) AS realized_pnl_total
                FROM transaction_entries te
                JOIN transactions t ON t.id = te.transaction_id
                WHERE t.transacted_at >= :start AND t.transacted_at < :end
                GROUP BY te.amount_currency
                ORDER BY te.amount_currency
                """;
        return jdbcTemplate.query(sql,
                new MapSqlParameterSource("start", start).addValue("end", end),
                (rs, rowNum) -> new CurrencyTotalsView(
                        rs.getString("currency"),
                        rs.getBigDecimal("debit_total"),
                        rs.getBigDecimal("credit_total"),
                        rs.getBigDecimal("realized_pnl_total")));
    }

    private static String accountTransactionsFrom(OffsetDateTime from, OffsetDateTime to) {
        // 한 거래에 같은 계좌 분개가 여러 건(매수의 자산·현금)이므로 거래 ID 를 먼저 중복 제거한다.
        StringBuilder sql = new StringBuilder("""
                FROM transactions t
                JOIN (SELECT DISTINCT transaction_id FROM transaction_entries WHERE account_id = :accountId) e
                  ON e.transaction_id = t.id
                WHERE 1 = 1""");
        // null 파라미터를 "(:from IS NULL OR ...)" 로 넘기면 PostgreSQL 이 타입을 추론하지 못하므로 조건을 조립한다.
        if (from != null) {
            sql.append(" AND t.transacted_at >= :from");
        }
        if (to != null) {
            sql.append(" AND t.transacted_at < :to");
        }
        return sql.toString();
    }

    private static MapSqlParameterSource periodParams(UUID accountId, OffsetDateTime from, OffsetDateTime to) {
        return new MapSqlParameterSource("accountId", accountId)
                .addValue("from", from)
                .addValue("to", to);
    }
}
