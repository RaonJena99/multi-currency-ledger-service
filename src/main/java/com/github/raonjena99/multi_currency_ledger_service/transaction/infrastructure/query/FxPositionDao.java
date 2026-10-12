package com.github.raonjena99.multi_currency_ledger_service.transaction.infrastructure.query;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import lombok.RequiredArgsConstructor;

/**
 * 회사 외화 포지션 집계입니다.
 *
 * <p>환전은 고객의 반대편에서 회사 FX 계정({@code SYSTEM_FX})에 통화별 분개를 남깁니다. 고객이 외화를 사면 회사는 그 외화를
 * 내주고(대변) 기준 통화를 받습니다(차변). 그래서 회사 포지션은 별도 테이블 없이 이 계정의 분개에서 바로 구해집니다.
 *
 * <p>고객 계좌의 기준 통화가 여럿일 수 있습니다. 분개의 금액({@code amount})은 그 거래의 기준 통화로 저장되므로 통화와
 * 기준 통화의 쌍으로 묶어야 장부가가 한 통화 단위로 더해집니다.
 */
@Repository
@RequiredArgsConstructor
public class FxPositionDao {

    /** 회사 외화 포지션이 기록되는 계정. 반올림 잔차(SYSTEM_FX_GAIN/LOSS)도 이 계정에 남는다. */
    private static final UUID SYSTEM_FX_ACCOUNT_ID = new UUID(0, 0);
    /** 환전 스프레드 수익이 기록되는 계정. */
    private static final UUID SYSTEM_FEE_ACCOUNT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String SPREAD_ASSET_CODE = "SYSTEM_FX_SPREAD";

    /**
     * 회사 FX 계정의 통화별 순수량과 장부가.
     *
     * <p>통화가 아닌 분개(반올림 잔차 {@code SYSTEM_FX_GAIN/LOSS})는 {@code SYSTEM_} 접두어로 걸러냅니다. 순수량은 차변 수량에서
     * 대변 수량을 뺀 값이라, 회사가 외화를 내줬으면 음수(판 포지션)입니다. 장부가는 같은 방식으로 기준 통화 금액을 합한 값입니다.
     */
    private static final String POSITIONS = """
            SELECT asset_code AS currency,
                   amount_currency AS base_currency,
                   SUM(CASE WHEN entry_type = 'DEBIT' THEN quantity ELSE -quantity END) AS net_quantity,
                   SUM(CASE WHEN entry_type = 'DEBIT' THEN amount ELSE -amount END) AS book_value
            FROM transaction_entries
            WHERE account_id = :systemFx
              AND asset_code NOT LIKE 'SYSTEM\\_%'
            GROUP BY asset_code, amount_currency
            ORDER BY amount_currency, asset_code
            """;

    private static final String SPREAD_REVENUE = """
            SELECT amount_currency AS base_currency, SUM(amount) AS revenue
            FROM transaction_entries
            WHERE account_id = :systemFee
              AND asset_code = :spreadAssetCode
              AND entry_type = 'CREDIT'
            GROUP BY amount_currency
            ORDER BY amount_currency
            """;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    /**
     * 회사 FX 계정의 한 (통화, 기준 통화) 포지션.
     *
     * @param currency     통화
     * @param baseCurrency 이 분개의 금액이 저장된 기준 통화
     * @param netQuantity  순수량(차변 − 대변). 회사가 외화를 내줬으면 음수
     * @param bookValue    장부가(기준 통화). 순수량과 같은 부호 규칙으로 합한 금액
     */
    public record PositionRow(String currency, String baseCurrency, BigDecimal netQuantity, BigDecimal bookValue) {}

    /**
     * 기준 통화별 누적 스프레드 수익.
     *
     * @param baseCurrency 기준 통화
     * @param revenue      환전으로 번 스프레드(기준 통화)의 합
     */
    public record SpreadRevenueRow(String baseCurrency, BigDecimal revenue) {}

    public List<PositionRow> findPositions() {
        return jdbcTemplate.query(POSITIONS, new MapSqlParameterSource("systemFx", SYSTEM_FX_ACCOUNT_ID),
                (rs, rowNum) -> new PositionRow(rs.getString("currency"), rs.getString("base_currency"),
                        rs.getBigDecimal("net_quantity"), rs.getBigDecimal("book_value")));
    }

    public List<SpreadRevenueRow> findSpreadRevenue() {
        return jdbcTemplate.query(SPREAD_REVENUE,
                new MapSqlParameterSource("systemFee", SYSTEM_FEE_ACCOUNT_ID)
                        .addValue("spreadAssetCode", SPREAD_ASSET_CODE),
                (rs, rowNum) -> new SpreadRevenueRow(rs.getString("base_currency"), rs.getBigDecimal("revenue")));
    }
}
