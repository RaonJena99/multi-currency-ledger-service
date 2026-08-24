package com.github.raonjena99.multi_currency_ledger_service.regression;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.github.raonjena99.multi_currency_ledger_service.IntegrationTestSupport;
import com.github.raonjena99.multi_currency_ledger_service.account.domain.Account;
import com.github.raonjena99.multi_currency_ledger_service.account.infrastructure.AccountRepository;
import com.github.raonjena99.multi_currency_ledger_service.common.domain.CurrencyScaleResolver;
import com.github.raonjena99.multi_currency_ledger_service.common.domain.Money;
import com.github.raonjena99.multi_currency_ledger_service.common.model.AssetType;
import com.github.raonjena99.multi_currency_ledger_service.transaction.application.LedgerService;
import com.github.raonjena99.multi_currency_ledger_service.transaction.application.command.LedgerRecordingCommand;

/**
 * 원장 분개의 <b>전수 매트릭스</b> 검증입니다.
 *
 * <p>지금까지 놓친 결함들은 공통 패턴이 있었습니다. 코드를 읽어서는 정상으로 보이고, 대차평균
 * 불변식도 통과하며, 특정 통화 조합이나 특정 분개 유형에서만 값이 틀어졌습니다. 손으로 고른
 * 몇 개 케이스로는 그 조합을 밟지 못합니다.
 *
 * <p>그래서 이 테스트는
 * <ul>
 *   <li>거래 유형 × 결제 통화 × 기준 통화 × 환율 × 수량·단가 경계값을 <b>조합으로 전개</b>하고,</li>
 *   <li>각 조합을 실제 DB 에 기록시킨 뒤,</li>
 *   <li>기대값을 <b>프로덕션 공식과 독립적으로</b> 다시 계산해 엔트리별 값을 대조합니다.</li>
 * </ul>
 *
 * <p>불변식(대차 일치)만 보지 않는 것이 핵심입니다. 매도 엔트리는
 * {@code amount + pnl = sellPrice×qty×rate} 로 대수적으로 상쇄되므로, 평균 원가의 단위가
 * 틀려도 대차는 정확히 맞습니다. 값 자체를 봐야 잡힙니다.
 */
@DisplayName("전수 검증: 원장 분개 매트릭스")
class LedgerMatrixTest extends IntegrationTestSupport {

    @Autowired private LedgerService ledgerService;
    @Autowired private AccountRepository accountRepository;
    @Autowired private JdbcTemplate jdbc;

    @AfterEach
    void tearDown() {
        jdbc.execute("TRUNCATE TABLE transaction_entries, transactions CASCADE");
        deleteTestAccounts();
    }

    /**
     * 한 조합을 나타내는 케이스.
     *
     * @param tradeType    분개 유형
     * @param fiatCode     결제 통화
     * @param baseCurrency 기준 통화
     * @param rate         결제 통화 → 기준 통화 환율
     * @param quantity     수량
     * @param unitPrice    결제 통화 기준 단가
     * @param averageCost  기준 통화 기준 평균 원가. null 이면 실현 손익 없음
     */
    record Case(String tradeType, String fiatCode, String baseCurrency,
                String rate, String quantity, String unitPrice, String averageCost) {
        @Override
        public String toString() {
            return "%s %s→%s rate=%s qty=%s price=%s cost=%s"
                    .formatted(tradeType, fiatCode, baseCurrency, rate, quantity, unitPrice, averageCost);
        }
    }

    /** 결제 통화 / 기준 통화 / 환율 조합. 스케일 0·2·3 통화를 섞는다. */
    private static final String[][] CURRENCY_PAIRS = {
            {"KRW", "KRW", "1"},          // 동일 통화, 스케일 0
            {"USD", "USD", "1"},          // 동일 통화, 스케일 2
            {"USD", "KRW", "1300"},       // 스케일 2 → 0, 큰 환율
            {"KRW", "USD", "0.00077"},    // 스케일 0 → 2, 작은 환율
            {"USD", "JPY", "157"},        // 스케일 2 → 0
            {"USD", "BHD", "0.376"},      // 스케일 2 → 3
            {"USD", "KRW", "25000"},      // 극단적으로 큰 환율
    };

    /** 수량 / 단가 / 평균원가(기준통화) 조합. 정확히 나누어지는 값과 반올림을 강제하는 값을 섞는다. */
    private static final String[][] AMOUNTS = {
            {"1", "100", "100"},
            {"2", "1000", "800"},
            {"0.5", "1.01", "130000"},        // 결제 통화 반올림 강제
            {"0.333", "3.77", "1"},           // 소수 수량 + 소수 단가
            {"7", "3.33", "0.5"},
            {"0.00000001", "100000000", "1"}, // 극단적 스케일 차이
    };

    static Stream<Case> cases() {
        List<Case> cases = new ArrayList<>();
        for (String[] pair : CURRENCY_PAIRS) {
            for (String[] amt : AMOUNTS) {
                for (String type : new String[]{"BUY", "SELL", "FEE_DEDUCTION"}) {
                    // SELL 만 평균 원가를 사용한다. FEE_DEDUCTION 은 null 폴백 경로도 함께 본다.
                    String cost = switch (type) {
                        case "SELL" -> amt[2];
                        case "FEE_DEDUCTION" -> null;
                        default -> null;
                    };
                    cases.add(new Case(type, pair[0], pair[1], pair[2], amt[0], amt[1], cost));
                }
            }
        }
        // 수수료 보정은 수량 부호가 방향을 결정하므로 별도로 전개한다.
        for (String[] pair : CURRENCY_PAIRS) {
            cases.add(new Case("FEE_ADJUSTMENT", pair[0], pair[1], pair[2], "50", "1", "0"));
            cases.add(new Case("FEE_ADJUSTMENT", pair[0], pair[1], pair[2], "-50", "1", "0"));
        }
        return cases.stream();
    }

    // ---------- 독립 기대값 계산 (프로덕션 공식을 재사용하지 않는다) ----------

    private static int scaleOf(String currency) {
        return CurrencyScaleResolver.resolveScale(AssetType.FIAT, currency);
    }

    private static BigDecimal roundTo(BigDecimal value, String currency, RoundingMode mode) {
        return value.setScale(scaleOf(currency), mode);
    }

    private static BigDecimal minUnit(String currency) {
        return BigDecimal.ONE.movePointLeft(scaleOf(currency));
    }

    // ---------- 실행 ----------

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("cases")
    void matrix(Case c) {
        UUID accountId = UUID.randomUUID();
        accountRepository.saveAndFlush(Account.open(accountId, "MATRIX", c.baseCurrency()));

        boolean assetIsFiat = "FEE_ADJUSTMENT".equals(c.tradeType());
        AssetType assetType = assetIsFiat ? AssetType.FIAT : AssetType.CRYPTO;
        String assetCode = assetIsFiat ? c.fiatCode() : "BTC";

        BigDecimal rate = new BigDecimal(c.rate());
        BigDecimal qty = Money.of(c.quantity(), assetType, assetCode).getAmount();
        BigDecimal price = new BigDecimal(c.unitPrice());
        BigDecimal cost = c.averageCost() == null ? null : new BigDecimal(c.averageCost());

        UUID tradeId = UUID.randomUUID();
        LedgerRecordingCommand cmd = new LedgerRecordingCommand(
                tradeId, accountId, assetCode, assetType, c.fiatCode(), c.baseCurrency(), c.tradeType(),
                Money.of(c.quantity(), assetType, assetCode), price,
                BigDecimal.ONE, rate, cost, false, OffsetDateTime.now());

        // 1) 예외 없이 영속화되어야 한다.
        ledgerService.recordDoubleEntry(cmd);

        List<Map<String, Object>> entries = jdbc.queryForList(
                "SELECT entry_type, asset_code, quantity, unit_price, amount, realized_pnl, exchange_rate "
                        + "FROM transaction_entries WHERE transaction_id = ? ORDER BY id", tradeId);

        if ("FEE_ADJUSTMENT".equals(c.tradeType()) && qty.signum() == 0) {
            assertThat(entries).isEmpty();
            return;
        }
        assertThat(entries).as("분개가 기록되어야 한다: %s", c).isNotEmpty();

        BigDecimal debit = BigDecimal.ZERO;
        BigDecimal credit = BigDecimal.ZERO;
        BigDecimal pnlTotal = BigDecimal.ZERO;
        BigDecimal plug = BigDecimal.ZERO;

        for (var e : entries) {
            BigDecimal amount = (BigDecimal) e.get("amount");
            BigDecimal pnl = (BigDecimal) e.get("realized_pnl");
            String code = String.valueOf(e.get("asset_code"));

            if ("DEBIT".equals(e.get("entry_type"))) debit = debit.add(amount);
            else credit = credit.add(amount);
            if (pnl != null) pnlTotal = pnlTotal.add(pnl);
            if (code.startsWith("SYSTEM_FX")) plug = plug.add(amount.abs());
        }

        // 2) 대차 불변식 (필요조건이지만 충분조건이 아니다)
        assertThat(debit)
                .as("차변 == 대변 + 실현손익: %s", c)
                .isEqualByComparingTo(credit.add(pnlTotal));

        // 3) 실현 손익은 매도에서만 발생한다.
        if (!"SELL".equals(c.tradeType())) {
            assertThat(pnlTotal)
                    .as("%s 에는 실현 손익이 발생해서는 안 된다. 센티넬 단위가 틀리면 가짜 손익이 생긴다: %s",
                            c.tradeType(), c)
                    .isEqualByComparingTo("0");
        }

        // 4) 엔트리별 값을 독립 계산과 대조한다.
        BigDecimal tolerance = minUnit(c.baseCurrency())
                .multiply(BigDecimal.valueOf(entries.size() + 1L))
                .add(minUnit(c.fiatCode()).multiply(rate.abs())
                        .multiply(BigDecimal.valueOf(entries.size())));

        switch (c.tradeType()) {
            case "BUY" -> {
                // 고객이 지불하는 금액은 올림
                BigDecimal fiatPaid = roundTo(price.multiply(qty), c.fiatCode(), RoundingMode.UP);
                BigDecimal expectedAsset = roundTo(price.multiply(qty).multiply(rate), c.baseCurrency(), RoundingMode.HALF_EVEN);
                BigDecimal expectedFiat = roundTo(fiatPaid.multiply(rate), c.baseCurrency(), RoundingMode.HALF_EVEN);

                // 반올림 "방향"은 허용 오차(= minUnit×rate)와 크기가 같아서 amount 비교로는 검출되지 않는다.
                // 결제 통화 단위의 quantity 를 오차 없이 대조해야 방향 오류가 드러난다.
                assertThat(quantityOf(entries, c.fiatCode(), "CREDIT"))
                        .as("지불액은 올림이어야 한다. 내림/사사오입이면 청구액이 깎인다: %s", c)
                        .isEqualByComparingTo(fiatPaid);

                assertThat(amountOf(entries, assetCode, "DEBIT"))
                        .as("매수 자산 차변 = round(price×qty×rate): %s", c)
                        .isCloseTo(expectedAsset, within(tolerance));
                assertThat(amountOf(entries, c.fiatCode(), "CREDIT"))
                        .as("매수 법정화폐 대변 = round(올림청구액×rate): %s", c)
                        .isCloseTo(expectedFiat, within(tolerance));
            }
            case "SELL" -> {
                // 고객이 수취하는 금액은 내림
                BigDecimal fiatReceived = roundTo(price.multiply(qty), c.fiatCode(), RoundingMode.DOWN);
                BigDecimal expectedFiat = roundTo(fiatReceived.multiply(rate), c.baseCurrency(), RoundingMode.HALF_EVEN);
                BigDecimal expectedDisposal = roundTo(cost.multiply(qty), c.baseCurrency(), RoundingMode.HALF_EVEN);
                BigDecimal expectedPnl = roundTo(price.multiply(rate).subtract(cost).multiply(qty),
                        c.baseCurrency(), RoundingMode.HALF_EVEN);

                assertThat(quantityOf(entries, c.fiatCode(), "DEBIT"))
                        .as("수취액은 내림이어야 한다. 올림/사사오입이면 통화가 창출된다: %s", c)
                        .isEqualByComparingTo(fiatReceived);

                assertThat(amountOf(entries, c.fiatCode(), "DEBIT"))
                        .as("매도 법정화폐 차변 = round(내림수취액×rate): %s", c)
                        .isCloseTo(expectedFiat, within(tolerance));
                assertThat(amountOf(entries, assetCode, "CREDIT"))
                        .as("처분 금액 = round(기준통화 평균원가×qty). 단위가 틀리면 환율배수만큼 부풀려진다: %s", c)
                        .isCloseTo(expectedDisposal, within(tolerance));
                assertThat(pnlTotal)
                        .as("실현손익 = round((price×rate − cost)×qty): %s", c)
                        .isCloseTo(expectedPnl, within(tolerance));
            }
            case "FEE_DEDUCTION" -> {
                BigDecimal expected = roundTo(price.multiply(qty).multiply(rate), c.baseCurrency(), RoundingMode.HALF_EVEN);
                assertThat(debit)
                        .as("수수료 차감은 고객 대변 == 시스템 차변: %s", c)
                        .isCloseTo(expected, within(tolerance));
            }
            case "FEE_ADJUSTMENT" -> {
                BigDecimal expected = roundTo(qty.abs().multiply(rate), c.baseCurrency(), RoundingMode.HALF_EVEN);
                assertThat(debit)
                        .as("수수료 보정 금액 = round(|qty|×rate): %s", c)
                        .isCloseTo(expected, within(tolerance));
            }
            default -> throw new IllegalStateException("unhandled: " + c.tradeType());
        }

        // 5) 플러그는 반올림 수준을 넘지 않는다.
        assertThat(plug)
                .as("플러그가 반올림 허용치를 넘으면 계산 오류를 숨기는 것이다: %s", c)
                .isLessThanOrEqualTo(tolerance);
    }

    private static org.assertj.core.data.Offset<BigDecimal> within(BigDecimal tolerance) {
        return org.assertj.core.data.Offset.offset(tolerance);
    }

    private BigDecimal quantityOf(List<Map<String, Object>> entries, String assetCode, String entryType) {
        return entries.stream()
                .filter(e -> assetCode.equals(e.get("asset_code")) && entryType.equals(e.get("entry_type")))
                .map(e -> (BigDecimal) e.get("quantity"))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal amountOf(List<Map<String, Object>> entries, String assetCode, String entryType) {
        return entries.stream()
                .filter(e -> assetCode.equals(e.get("asset_code")) && entryType.equals(e.get("entry_type")))
                .map(e -> (BigDecimal) e.get("amount"))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
