package com.github.raonjena99.multi_currency_ledger_service.reconciliation.infrastructure.query;

import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.github.raonjena99.multi_currency_ledger_service.common.domain.Money;
import com.github.raonjena99.multi_currency_ledger_service.common.model.AssetType;

import lombok.RequiredArgsConstructor;

@Repository
@RequiredArgsConstructor
public class InternalTransactionQueryDao {
    private final NamedParameterJdbcTemplate jdbcTemplate;

    public List<InternalTransactionCandidate> fetchCandidatesForPeriod(OffsetDateTime start, OffsetDateTime end) {
        String sql = """
            SELECT t.id AS transaction_id, 
                    t.transacted_at, 
                    t.description, 
                    te.amount, 
                    te.amount_asset_type AS asset_type
            FROM transactions t
            INNER JOIN transaction_entries te ON t.id = te.transaction_id
            WHERE te.entry_type = 'CREDIT' 
            AND t.transacted_at >= :start AND t.transacted_at < :end
        """;

        Timestamp startTs = Timestamp.from(start.toInstant());
        Timestamp endTs = Timestamp.from(end.toInstant());

        return jdbcTemplate.query(sql, 
            new MapSqlParameterSource()
                .addValue("start", startTs, java.sql.Types.TIMESTAMP)
                .addValue("end", endTs, java.sql.Types.TIMESTAMP),
            (rs, rowNum) -> {
                Timestamp ts = rs.getTimestamp("transacted_at");
                OffsetDateTime transactedAt = ts != null ? ts.toInstant().atOffset(ZoneOffset.UTC) : null;

                return new InternalTransactionCandidate(
                    UUID.fromString(rs.getString("transaction_id")),
                    transactedAt,
                    rs.getString("description"),
                    Money.of(rs.getBigDecimal("amount").stripTrailingZeros().toPlainString(), 
                            AssetType.valueOf(rs.getString("asset_type")))
                );
            }
        );
    }
}