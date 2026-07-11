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

/**
 * 대사(Reconciliation) 후보가 될 수 있는 내부 거래(InternalTransaction) 목록을
 * 데이터베이스(DB)에서 직접 조회하는 DAO(Data Access Object) 클래스입니다.
 */
@Repository
@RequiredArgsConstructor
public class InternalTransactionQueryDao {
    private final NamedParameterJdbcTemplate jdbcTemplate;

    /**
     * 특정 기간 내에 발생한 내부 거래 후보들을 조회합니다.
     * 대사 대상이 되는 대변(CREDIT) 거래만 필터링하여 가져옵니다.
     * 
     * @param start 조회 시작 일시 (OffsetDateTime)
     * @param end 조회 종료 일시 (OffsetDateTime)
     * @return 내부 거래 후보 목록 (List<InternalTransactionCandidate>)
     */
    public List<InternalTransactionCandidate> fetchCandidatesForPeriod(OffsetDateTime start, OffsetDateTime end) {
        // SQL 쿼리를 통해 대변(CREDIT) 거래 엔트리를 가진 거래의 ID, 시간, 내역, 금액 정보를 추출합니다.
        String sql = """
            SELECT t.id AS transaction_id, 
                    t.transacted_at, 
                    t.description, 
                    te.amount, 
                    te.amount_asset_type AS asset_type,
                    te.amount_currency AS currency
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
                            AssetType.valueOf(rs.getString("asset_type")), rs.getString("currency"))
                );
            }
        );
    }
}