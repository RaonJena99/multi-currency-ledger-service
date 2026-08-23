package com.github.raonjena99.multi_currency_ledger_service.portfolio.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

class PortfolioValuationTest {

    @Test
    void calculate_should_compute_correct_values() {
        BigDecimal totalQuantity = new BigDecimal("2");
        BigDecimal avgUnitPrice = new BigDecimal("50000");
        BigDecimal currentMarketPrice = new BigDecimal("60000");
        BigDecimal quoteToBaseExchangeRate = new BigDecimal("1"); // KRW -> KRW

        PortfolioValuation valuation = PortfolioValuation.calculate(
            totalQuantity, avgUnitPrice, currentMarketPrice, quoteToBaseExchangeRate
        );

        assertThat(valuation.currentMarketPrice()).isEqualByComparingTo("60000");
        assertThat(valuation.totalValue()).isEqualByComparingTo("120000");
        // unrealizedPnl = (60000 - 50000 * 1) * 2 = 20000
        assertThat(valuation.unrealizedPnl()).isEqualByComparingTo("20000");
    }

    @Test
    void calculate_should_apply_exchange_rate_correctly() {
        BigDecimal totalQuantity = new BigDecimal("1");
        BigDecimal avgUnitPrice = new BigDecimal("1000"); // 1000 USD
        BigDecimal currentMarketPrice = new BigDecimal("1500000"); // 1500000 KRW
        BigDecimal quoteToBaseExchangeRate = new BigDecimal("1200"); // 1 USD = 1200 KRW

        PortfolioValuation valuation = PortfolioValuation.calculate(
            totalQuantity, avgUnitPrice, currentMarketPrice, quoteToBaseExchangeRate
        );

        assertThat(valuation.currentMarketPrice()).isEqualByComparingTo("1500000");
        assertThat(valuation.totalValue()).isEqualByComparingTo("1500000");
        
        // converted avg = 1000 * 1200 = 1200000
        // unrealizedPnl = (1500000 - 1200000) * 1 = 300000
        assertThat(valuation.unrealizedPnl()).isEqualByComparingTo("300000");
    }
}
