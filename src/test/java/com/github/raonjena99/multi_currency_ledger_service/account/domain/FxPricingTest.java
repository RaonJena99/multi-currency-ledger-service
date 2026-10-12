package com.github.raonjena99.multi_currency_ledger_service.account.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Random;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.github.raonjena99.multi_currency_ledger_service.account.domain.FxPricing.Priced;

/**
 * 환전 가격 산정을 검증합니다. 스프레드는 회사의 수익이므로 어떤 입력에서도 고객이 시세보다 유리한 값을
 * 받아서는 안 되고, 우대율이 높을수록 고객이 손해를 덜 봐야 합니다.
 */
@DisplayName("환전 가격 산정")
class FxPricingTest {

    private static final BigDecimal SPREAD = new BigDecimal("0.0175");

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }

    @Test
    @DisplayName("외화를 살 때: 기준율 1,300 · 스프레드 1.75% · 우대 90% 이면 고객 환율은 1,302.275 이다")
    void buyingForeignCurrencyAddsTheDiscountedSpread() {
        Priced priced = FxPricing.price(FxDirection.BUY_FOREIGN, bd("1300000"), bd("1300"), SPREAD, bd("0.9"), 2);

        assertThat(priced.effectiveSpread()).isEqualByComparingTo("0.00175");
        assertThat(priced.customerRate()).isEqualByComparingTo("1302.275");
        // 1,300,000 / 1,302.275 = 998.2535... 이므로 고객에게 불리하게 내림해 998.25 달러를 받는다.
        assertThat(priced.receivedAmount()).isEqualByComparingTo("998.25");
    }

    @Test
    @DisplayName("외화를 팔 때: 기준율보다 낮은 환율을 적용한다 (1,300 → 1,297.725)")
    void sellingForeignCurrencySubtractsTheDiscountedSpread() {
        Priced priced = FxPricing.price(FxDirection.SELL_FOREIGN, bd("1000"), bd("1300"), SPREAD, bd("0.9"), 0);

        assertThat(priced.customerRate()).isEqualByComparingTo("1297.725");
        assertThat(priced.receivedAmount()).isEqualByComparingTo("1297725");
    }

    @Test
    @DisplayName("우대율이 0 이면 스프레드를 그대로 받고, 우대율이 1 이면 스프레드가 없다")
    void preferentialRateScalesTheSpread() {
        assertThat(FxPricing.effectiveSpread(SPREAD, BigDecimal.ZERO)).isEqualByComparingTo("0.0175");
        assertThat(FxPricing.effectiveSpread(SPREAD, bd("0.5"))).isEqualByComparingTo("0.00875");
        assertThat(FxPricing.effectiveSpread(SPREAD, BigDecimal.ONE)).isEqualByComparingTo("0");

        Priced none = FxPricing.price(FxDirection.BUY_FOREIGN, bd("1300000"), bd("1300"), SPREAD, BigDecimal.ZERO, 2);
        assertThat(none.customerRate()).isEqualByComparingTo("1322.75");
    }

    @Test
    @DisplayName("받을 금액은 받는 통화의 최소 단위로 내림한다 (사사오입이면 통화가 생긴다)")
    void receivedAmountIsRoundedDown() {
        // 1,000,000 / 1,302.275 = 767.886967... → 소수 둘째 자리 아래는 버려 767.88 이다.
        Priced priced = FxPricing.price(FxDirection.BUY_FOREIGN, bd("1000000"), bd("1300"), SPREAD, bd("0.9"), 2);

        BigDecimal exact = bd("1000000").divide(priced.customerRate(), 18, RoundingMode.DOWN);
        assertThat(priced.receivedAmount()).isLessThanOrEqualTo(exact);
        assertThat(priced.receivedAmount()).isEqualByComparingTo("767.88");
        assertThat(priced.receivedAmount().scale()).isEqualTo(2);
    }

    @Test
    @DisplayName("소수 자리가 없는 통화(JPY)로 받으면 정수로 내림한다")
    void zeroScaleCurrencyIsRoundedToWholeUnits() {
        // 기준 통화 KRW, 외화 JPY. 시세 9.5 원, 스프레드 1.75%, 우대 없음 → 고객 환율 9.666250
        Priced priced = FxPricing.price(FxDirection.BUY_FOREIGN, bd("100000"), bd("9.5"), SPREAD, BigDecimal.ZERO, 0);

        assertThat(priced.customerRate()).isEqualByComparingTo("9.666250");
        assertThat(priced.receivedAmount()).isEqualByComparingTo("10345");
        assertThat(priced.receivedAmount().scale()).isZero();
    }

    @Test
    @DisplayName("시세가 1 보다 훨씬 작은 통화쌍(기준 통화 USD, 외화 KRW)도 정밀도를 잃지 않는다")
    void verySmallRatesKeepPrecision() {
        // 1 KRW = 0.00077 USD
        Priced priced = FxPricing.price(FxDirection.BUY_FOREIGN, bd("100"), bd("0.00077"), SPREAD, BigDecimal.ZERO, 0);

        assertThat(priced.customerRate()).isEqualByComparingTo("0.000783475");
        // 100 / 0.000783475 = 127,636.8... → 127,636 원
        assertThat(priced.receivedAmount()).isEqualByComparingTo("127636");
    }

    @Test
    @DisplayName("고객 환율은 회사에 유리한 방향으로 소수 18자리에서 맺는다")
    void customerRateRoundsInTheCompanysFavour() {
        BigDecimal market = bd("1.000000000000000001");
        BigDecimal effectiveSpread = bd("0.000000000000000003");

        BigDecimal buy = FxPricing.customerRate(FxDirection.BUY_FOREIGN, market, effectiveSpread);
        BigDecimal sell = FxPricing.customerRate(FxDirection.SELL_FOREIGN, market, effectiveSpread);

        // 정확한 값은 소수 36자리다. 살 때는 올림, 팔 때는 내림이라 둘 다 시세에서 벌어진다.
        assertThat(buy.scale()).isEqualTo(18);
        assertThat(sell.scale()).isEqualTo(18);
        assertThat(buy).isGreaterThan(market);
        assertThat(sell).isLessThan(market);
    }

    @Test
    @DisplayName("잘못된 입력은 거부한다")
    void rejectsInvalidInputs() {
        assertThatThrownBy(() -> FxPricing.effectiveSpread(SPREAD, bd("-0.01")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FxPricing.effectiveSpread(SPREAD, bd("1.01")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FxPricing.effectiveSpread(bd("-0.01"), BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FxPricing.effectiveSpread(BigDecimal.ONE, BigDecimal.ZERO))
                .as("스프레드가 100% 이상이면 파는 쪽 환율이 0 이하가 된다")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FxPricing.price(FxDirection.BUY_FOREIGN, bd("100"), BigDecimal.ZERO, SPREAD,
                BigDecimal.ZERO, 2)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FxPricing.price(FxDirection.BUY_FOREIGN, BigDecimal.ZERO, bd("1300"), SPREAD,
                BigDecimal.ZERO, 2)).isInstanceOf(IllegalArgumentException.class);
    }

    // ── 속성: 어떤 입력에서도 돈을 만들 수 없다 ───────────────────────────────

    private static final String[] MARKET_RATES = {"1300", "1300.7654321", "0.00077", "157.30", "9.5", "0.9231", "25000"};
    private static final String[] PREFERENTIAL_RATES = {"0", "0.1", "0.5", "0.9"};

    @Test
    @DisplayName("속성: 고객이 받는 금액의 시세 가치는 낸 금액을 넘지 않는다 (무작위 5,000건)")
    void customerNeverReceivesMoreThanMarketValue() {
        Random random = new Random(114L);
        for (int i = 0; i < 5_000; i++) {
            BigDecimal market = bd(MARKET_RATES[random.nextInt(MARKET_RATES.length)]);
            BigDecimal preferential = bd(PREFERENTIAL_RATES[random.nextInt(PREFERENTIAL_RATES.length)]);
            int scale = random.nextInt(4);
            BigDecimal paid = BigDecimal.valueOf(1 + random.nextInt(2_000_000), random.nextInt(3));

            Priced buy = FxPricing.price(FxDirection.BUY_FOREIGN, paid, market, SPREAD, preferential, scale);
            // 기준 통화로 환산한 가치: 받은 외화 × 시세 ≤ 낸 기준 통화
            assertThat(buy.receivedAmount().multiply(market))
                    .as("사는 쪽 paid=%s market=%s pref=%s", paid, market, preferential)
                    .isLessThanOrEqualTo(paid);
            assertThat(buy.customerRate()).isGreaterThanOrEqualTo(market);

            Priced sell = FxPricing.price(FxDirection.SELL_FOREIGN, paid, market, SPREAD, preferential, scale);
            // 낸 외화의 시세 가치 ≥ 받은 기준 통화
            assertThat(sell.receivedAmount())
                    .as("파는 쪽 paid=%s market=%s pref=%s", paid, market, preferential)
                    .isLessThanOrEqualTo(paid.multiply(market));
            assertThat(sell.customerRate()).isLessThanOrEqualTo(market);
        }
    }

    @Test
    @DisplayName("속성: 우대율이 높을수록 고객이 받는 금액은 줄지 않는다")
    void higherPreferentialRateNeverHurtsTheCustomer() {
        Random random = new Random(1_114L);
        for (int i = 0; i < 2_000; i++) {
            BigDecimal market = bd(MARKET_RATES[random.nextInt(MARKET_RATES.length)]);
            BigDecimal paid = BigDecimal.valueOf(1 + random.nextInt(2_000_000), random.nextInt(3));
            BigDecimal low = bd("0.1");
            BigDecimal high = bd("0.9");

            for (FxDirection direction : FxDirection.values()) {
                BigDecimal withLow = FxPricing.price(direction, paid, market, SPREAD, low, 2).receivedAmount();
                BigDecimal withHigh = FxPricing.price(direction, paid, market, SPREAD, high, 2).receivedAmount();
                assertThat(withHigh).as("%s paid=%s market=%s", direction, paid, market)
                        .isGreaterThanOrEqualTo(withLow);
            }
        }
    }

    @Test
    @DisplayName("속성: 시세가 그대로면 기준 통화 → 외화 → 기준 통화 왕복에서 고객은 손해를 본다")
    void roundTripAtConstantMarketCostsTheCustomer() {
        Random random = new Random(2_114L);
        for (int i = 0; i < 2_000; i++) {
            BigDecimal market = bd(MARKET_RATES[random.nextInt(MARKET_RATES.length)]);
            BigDecimal preferential = bd(PREFERENTIAL_RATES[random.nextInt(PREFERENTIAL_RATES.length)]);
            BigDecimal start = BigDecimal.valueOf(10_000 + random.nextInt(5_000_000));

            BigDecimal foreign = FxPricing.price(FxDirection.BUY_FOREIGN, start, market, SPREAD, preferential, 2)
                    .receivedAmount();
            if (foreign.signum() == 0) {
                continue;
            }
            BigDecimal back = FxPricing.price(FxDirection.SELL_FOREIGN, foreign, market, SPREAD, preferential, 0)
                    .receivedAmount();

            assertThat(back).as("start=%s market=%s pref=%s", start, market, preferential)
                    .isLessThanOrEqualTo(start);
        }
    }
}
