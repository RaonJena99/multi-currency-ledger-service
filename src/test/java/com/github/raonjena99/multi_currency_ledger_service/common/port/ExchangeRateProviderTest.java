package com.github.raonjena99.multi_currency_ledger_service.common.port;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Map;

import org.junit.jupiter.api.Test;

class ExchangeRateProviderTest {

    @Test
    void testDefaultGetExchangeRates() {
        ExchangeRateProvider provider = new ExchangeRateProvider() {
            @Override
            public ExchangeRate getExchangeRate(String baseAsset, String targetAsset) {
                if (baseAsset.equals("USD")) {
                    return new ExchangeRate(new BigDecimal("1300"), false);
                }
                return new ExchangeRate(new BigDecimal("1"), false);
            }
        };

        Map<String, ExchangeRateProvider.ExchangeRate> rates = provider.getExchangeRates(Arrays.asList("KRW", "USD"), "KRW");
        
        assertThat(rates).hasSize(2);
        assertThat(rates.get("KRW").rate()).isEqualByComparingTo("1");
        assertThat(rates.get("USD").rate()).isEqualByComparingTo("1300");
    }
}
