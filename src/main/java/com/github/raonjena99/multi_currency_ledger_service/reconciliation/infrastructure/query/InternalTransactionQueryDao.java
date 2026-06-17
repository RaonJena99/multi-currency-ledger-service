package com.github.raonjena99.multi_currency_ledger_service.reconciliation.infrastructure.query;

import java.time.OffsetDateTime;
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
            AND t.transacted_at BETWEEN :start AND :end
        """;

        return jdbcTemplate.query(sql, 
            new MapSqlParameterSource().addValue("start", start).addValue("end", end),
            (rs, rowNum) -> new InternalTransactionCandidate(
                UUID.fromString(rs.getString("transaction_id")),
                rs.getObject("transacted_at", OffsetDateTime.class),
                rs.getString("description"),
                Money.of(rs.getBigDecimal("amount").stripTrailingZeros().toPlainString(), 
                AssetType.valueOf(rs.getString("asset_type")))
            )
        );
    }
}
