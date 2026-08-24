package com.github.raonjena99.multi_currency_ledger_service.account.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.github.raonjena99.multi_currency_ledger_service.account.infrastructure.MonthlyAccountLedgerRepository;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

@ExtendWith(MockitoExtension.class)
class AccountMetricsConfigurationTest {

    @Mock
    private MonthlyAccountLedgerRepository ledgerRepository;

    @Test
    void initializeMetrics_should_use_default_codes_if_empty() {
        MeterRegistry meterRegistry = new SimpleMeterRegistry();
        AccountMetricsConfiguration config = new AccountMetricsConfiguration(meterRegistry, ledgerRepository);
        
        when(ledgerRepository.findDistinctFiatCodes()).thenReturn(Collections.emptyList(), Collections.emptyList()); // Second one for refreshFiatBalances call
        when(ledgerRepository.sumLatestBalanceByAssetCode("KRW")).thenReturn(new BigDecimal("1000"));
        when(ledgerRepository.sumLatestBalanceByAssetCode("USD")).thenReturn(new BigDecimal("2000"));

        config.initializeMetrics();

        assertThat(meterRegistry.find("platform.total.fiat.balance").tag("currency", "KRW").gauge().value()).isEqualTo(1000.0);
        assertThat(meterRegistry.find("platform.total.fiat.balance").tag("currency", "USD").gauge().value()).isEqualTo(2000.0);
    }

    @Test
    void initializeMetrics_should_use_existing_codes_if_not_empty() {
        MeterRegistry meterRegistry = new SimpleMeterRegistry();
        AccountMetricsConfiguration config = new AccountMetricsConfiguration(meterRegistry, ledgerRepository);
        
        when(ledgerRepository.findDistinctFiatCodes()).thenReturn(Arrays.asList("EUR"));
        when(ledgerRepository.sumLatestBalanceByAssetCode("EUR")).thenReturn(new BigDecimal("1500"));

        config.initializeMetrics();

        assertThat(meterRegistry.find("platform.total.fiat.balance").tag("currency", "EUR").gauge().value()).isEqualTo(1500.0);
    }

    @Test
    void refreshFiatBalances_should_detect_new_currency_and_cache() {
        MeterRegistry meterRegistry = new SimpleMeterRegistry();
        AccountMetricsConfiguration config = new AccountMetricsConfiguration(meterRegistry, ledgerRepository);
        
        when(ledgerRepository.findDistinctFiatCodes()).thenReturn(Arrays.asList("EUR"));
        when(ledgerRepository.sumLatestBalanceByAssetCode("EUR")).thenReturn(new BigDecimal("3000"));

        config.refreshFiatBalances();

        assertThat(meterRegistry.find("platform.total.fiat.balance").tag("currency", "EUR").gauge().value()).isEqualTo(3000.0);
    }

    @Test
    void refreshFiatBalances_should_handle_db_exception_gracefully() {
        MeterRegistry meterRegistry = new SimpleMeterRegistry();
        AccountMetricsConfiguration config = new AccountMetricsConfiguration(meterRegistry, ledgerRepository);
        
        when(ledgerRepository.findDistinctFiatCodes()).thenReturn(Arrays.asList("KRW"));
        when(ledgerRepository.sumLatestBalanceByAssetCode("KRW")).thenThrow(new RuntimeException("DB down"));

        // Should not throw
        config.refreshFiatBalances();

        // Should remain 0.0 as it was initialized
        assertThat(meterRegistry.find("platform.total.fiat.balance").tag("currency", "KRW").gauge().value()).isEqualTo(0.0);
    }

    @Test
    void refreshFiatBalances_should_update_existing_currency() {
        MeterRegistry meterRegistry = new SimpleMeterRegistry();
        AccountMetricsConfiguration config = new AccountMetricsConfiguration(meterRegistry, ledgerRepository);
        
        // initialize to create the gauge
        when(ledgerRepository.findDistinctFiatCodes()).thenReturn(Arrays.asList("JPY"));
        when(ledgerRepository.sumLatestBalanceByAssetCode("JPY")).thenReturn(new BigDecimal("500"));
        config.initializeMetrics();

        // now refresh and update it
        when(ledgerRepository.sumLatestBalanceByAssetCode("JPY")).thenReturn(new BigDecimal("600"));
        config.refreshFiatBalances();

        assertThat(meterRegistry.find("platform.total.fiat.balance").tag("currency", "JPY").gauge().value()).isEqualTo(600.0);
    }

    @Test
    void refreshFiatBalances_should_handle_null_balance() {
        MeterRegistry meterRegistry = new SimpleMeterRegistry();
        AccountMetricsConfiguration config = new AccountMetricsConfiguration(meterRegistry, ledgerRepository);
        
        when(ledgerRepository.findDistinctFiatCodes()).thenReturn(Arrays.asList("KRW"));
        when(ledgerRepository.sumLatestBalanceByAssetCode("KRW")).thenReturn(null);

        config.refreshFiatBalances();

        assertThat(meterRegistry.find("platform.total.fiat.balance").tag("currency", "KRW").gauge().value()).isEqualTo(0.0);
    }
}
