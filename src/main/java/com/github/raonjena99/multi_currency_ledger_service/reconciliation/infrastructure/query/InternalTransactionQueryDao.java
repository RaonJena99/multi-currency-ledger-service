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

    /**
     * 하루치 후보 조회 상한. 상한이 없으면 대량 거래일에 하루 분량 전체가 메모리에 올라옵니다.
     */
    private static final int MAX_CANDIDATES_PER_DAY = 50_000;

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
        // 후보는 반드시 <b>거래 단위</b>로 집계해야 합니다.
        //
        // 엔트리 단위로 뽑으면 한 거래가 여러 후보로 중복 등장합니다. 특히 반올림 잔차를 흡수하는
        // SYSTEM_FX_GAIN 엔트리도 CREDIT 이므로 가짜 후보가 됩니다. 그리고 매칭 비교 대상이
        // 거래 총액이 아니라 개별 엔트리 금액이 되어 금액 대조 자체가 틀어집니다.
        //
        // 시스템 계정 엔트리(SYSTEM_*, FEE_*)는 외부 정산 대상이 아니므로 제외합니다.
        // 무한 적재를 막기 위해 상한을 둡니다.
        //
        // "이미 소비된 내부 거래" 판단은 반드시 external_settlement.matched_internal_transaction_id
        // 로 해야 합니다. 이 컬럼은 정산 상태 변경과 <b>같은 트랜잭션</b>에서 갱신됩니다.
        // settlement_match 로 판단하면, 그 테이블은 독립 트랜잭션(REQUIRES_NEW)에서 커밋되므로
        // 청크가 롤백된 뒤 남은 고아 행이 후보를 영구히 가려 정산이 다시는 매칭되지 못합니다.
        // settlement_match 는 1:1 유일성 강제 전용이며, 후보 가시성에는 관여하지 않습니다.
        String sql = """
            SELECT t.id AS transaction_id,
                    t.transacted_at,
                    t.description,
                    MIN(te.account_id::text) AS account_id,
                    SUM(te.amount) AS amount,
                    MIN(te.amount_asset_type) AS asset_type,
                    MIN(te.amount_currency) AS currency
            FROM transactions t
            INNER JOIN transaction_entries te ON t.id = te.transaction_id
            LEFT JOIN external_settlement es ON t.id = es.matched_internal_transaction_id
            WHERE te.entry_type = 'CREDIT'
              AND t.transacted_at >= :start AND t.transacted_at < :end
              AND es.id IS NULL
              AND t.transaction_type NOT IN ('FEE_DEDUCTION', 'FEE_ADJUSTMENT')
              AND te.asset_code NOT LIKE 'SYSTEM\\_%'
            GROUP BY t.id, t.transacted_at, t.description
            HAVING COUNT(DISTINCT te.amount_currency) = 1
            ORDER BY t.transacted_at ASC, t.id ASC
            LIMIT :maxCandidates
        """;

        return jdbcTemplate.query(sql,
            new MapSqlParameterSource()
                .addValue("start", start)
                .addValue("end", end)
                .addValue("maxCandidates", MAX_CANDIDATES_PER_DAY),
            (rs, rowNum) -> {
                Timestamp ts = rs.getTimestamp("transacted_at");
                OffsetDateTime transactedAt = ts != null ? ts.toInstant().atOffset(ZoneOffset.UTC) : null;

                return new InternalTransactionCandidate(
                    UUID.fromString(rs.getString("transaction_id")),
                    UUID.fromString(rs.getString("account_id")),
                    transactedAt,
                    rs.getString("description"),
                    Money.of(rs.getBigDecimal("amount"), 
                            AssetType.valueOf(rs.getString("asset_type")), rs.getString("currency"))
                );
            }
        );
    }

    /**
     * 내부 거래의 귀속 계좌 ID 를 조회합니다.
     *
     * <p>UUID 는 문자열이 아니라 UUID 로 바인딩합니다. 문자열로 넘기면 드라이버의 타입 추론에
     * 의존하게 되어 설정에 따라 {@code operator does not exist: uuid = character varying} 로
     * 깨질 수 있습니다.
     *
     * @param transactionId 내부 거래 ID
     * @return 귀속 계좌 ID
     */
    public UUID findAccountIdByTransactionId(UUID transactionId) {
        String sql = """
            SELECT account_id FROM transaction_entries
            WHERE transaction_id = :id AND entry_type = 'CREDIT'
            ORDER BY id ASC
            LIMIT 1
        """;
        List<UUID> results = jdbcTemplate.query(sql,
            new MapSqlParameterSource("id", transactionId),
            (rs, rowNum) -> UUID.fromString(rs.getString("account_id"))
        );
        if (results.isEmpty()) {
            throw new IllegalArgumentException("Transaction not found: " + transactionId);
        }
        return results.get(0);
    }
}