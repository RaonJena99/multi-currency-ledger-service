package com.github.raonjena99.multi_currency_ledger_service.common.infrastructure.adapter;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.github.raonjena99.multi_currency_ledger_service.common.port.ExchangeRateProvider.ExchangeRate;

class DummyExchangeRateAdapterTest {

    @Test
    @DisplayName("더미 환율 제공기 분기 커버리지 테스트")
    void testDummyExchangeRate() {
        DummyExchangeRateAdapter adapter = new DummyExchangeRateAdapter();

        // 1. Same currency
        ExchangeRate same = adapter.getExchangeRate("BTC", "BTC");
        assertThat(same.rate()).isEqualByComparingTo(BigDecimal.ONE);
        assertThat(same.isStale()).isFalse();

        // 2. target is BTC
        ExchangeRate toBtc = adapter.getExchangeRate("KRW", "BTC");
        assertThat(toBtc.rate()).isEqualByComparingTo(BigDecimal.ONE.divide(new BigDecimal("100000000.00"), 18, java.math.RoundingMode.HALF_EVEN));

        // 3. base is BTC
        ExchangeRate fromBtc = adapter.getExchangeRate("BTC", "KRW");
        assertThat(fromBtc.rate()).isEqualByComparingTo(new BigDecimal("100000000.00"));

        // 4. Other currencies
        ExchangeRate other = adapter.getExchangeRate("USD", "KRW");
        assertThat(other.rate()).isEqualByComparingTo(BigDecimal.ONE);
    }

    private static DummyExchangeRateAdapter withRates(String base, String quote, String rate) {
        DummyExchangeRateAdapter adapter = new DummyExchangeRateAdapter();
        adapter.setRates(java.util.Map.of(base, java.util.Map.of(quote, new BigDecimal(rate))));
        return adapter;
    }

    @Test
    @DisplayName("설정한 환율이 있으면 그 값을 쓴다 (로컬에서 현실적인 환율로 환전을 확인하기 위함)")
    void usesConfiguredRates() {
        DummyExchangeRateAdapter adapter = withRates("USD", "KRW", "1300");

        ExchangeRate configured = adapter.getExchangeRate("USD", "KRW");

        assertThat(configured.rate()).isEqualByComparingTo("1300");
        assertThat(configured.isStale()).isFalse();
    }

    @Test
    @DisplayName("한쪽 방향만 설정해도 반대 방향은 역수로 계산한다")
    void derivesTheInverseDirection() {
        DummyExchangeRateAdapter adapter = withRates("USD", "KRW", "1250");

        assertThat(adapter.getExchangeRate("KRW", "USD").rate()).isEqualByComparingTo("0.0008");
    }

    @Test
    @DisplayName("설정의 통화 코드는 대소문자를 가리지 않는다 (환경 변수로 지정하면 소문자가 된다)")
    void configuredCodesAreCaseInsensitive() {
        DummyExchangeRateAdapter adapter = withRates("usd", "krw", "1300");

        assertThat(adapter.getExchangeRate("USD", "KRW").rate()).isEqualByComparingTo("1300");
    }

    @Test
    @DisplayName("설정하지 않은 쌍과 같은 통화, BTC 는 기존 동작 그대로다")
    void unconfiguredPairsKeepTheDefaults() {
        DummyExchangeRateAdapter adapter = withRates("USD", "KRW", "1300");

        assertThat(adapter.getExchangeRate("EUR", "KRW").rate()).isEqualByComparingTo(BigDecimal.ONE);
        assertThat(adapter.getExchangeRate("USD", "USD").rate()).isEqualByComparingTo(BigDecimal.ONE);
        assertThat(adapter.getExchangeRate("BTC", "KRW").rate()).isEqualByComparingTo("100000000.00");
    }
}
