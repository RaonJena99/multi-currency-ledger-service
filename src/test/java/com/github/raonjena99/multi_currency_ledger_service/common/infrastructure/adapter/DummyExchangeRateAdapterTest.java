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
}
