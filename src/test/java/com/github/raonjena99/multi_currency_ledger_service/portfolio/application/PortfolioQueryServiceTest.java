package com.github.raonjena99.multi_currency_ledger_service.portfolio.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.github.raonjena99.multi_currency_ledger_service.account.AccountApi;
import com.github.raonjena99.multi_currency_ledger_service.common.port.ExchangeRateProvider;
import com.github.raonjena99.multi_currency_ledger_service.portfolio.application.dto.PortfolioCacheDto;
import com.github.raonjena99.multi_currency_ledger_service.portfolio.application.dto.PortfolioSummaryResponse;
import com.github.raonjena99.multi_currency_ledger_service.portfolio.application.port.PortfolioCachePort;

@ExtendWith(MockitoExtension.class)
class PortfolioQueryServiceTest {

    @Mock private ExchangeRateProvider exchangeRateProvider;
    @Mock private AccountApi accountApi;
    @Mock private PortfolioCachePort portfolioCachePort;

    @InjectMocks
    private PortfolioQueryService service;

    @Test
    void getPortfolioSummary_should_use_cache_when_available() {
        UUID accountId = UUID.randomUUID();
        when(accountApi.getBaseCurrency(accountId)).thenReturn("KRW");
        
        PortfolioCacheDto.AssetBalance balance = new PortfolioCacheDto.AssetBalance("BTC", new BigDecimal("1"), new BigDecimal("10000"), "KRW");
        PortfolioCacheDto cacheDto = new PortfolioCacheDto(accountId, "KRW", List.of(balance));
        when(portfolioCachePort.getPortfolioCache(accountId)).thenReturn(Optional.of(cacheDto));

        when(exchangeRateProvider.getExchangeRates(List.of("BTC", "KRW"), "KRW"))
            .thenReturn(Map.of(
                "BTC", new ExchangeRateProvider.ExchangeRate(new BigDecimal("15000"), false),
                "KRW", new ExchangeRateProvider.ExchangeRate(BigDecimal.ONE, false)
            ));

        PortfolioSummaryResponse response = service.getPortfolioSummary(accountId);

        assertThat(response.accountId()).isEqualTo(accountId);
        assertThat(response.totalAssetValue()).isEqualByComparingTo("15000");
        assertThat(response.totalUnrealizedPnl()).isEqualByComparingTo("5000"); // 15000 - 10000
        assertThat(response.isStaleData()).isFalse();
        
        verify(portfolioCachePort, never()).tryAcquireLock(anyString(), anyInt());
    }

    @Test
    void getPortfolioSummary_should_fetch_from_api_and_cache_when_cache_miss() {
        UUID accountId = UUID.randomUUID();
        when(accountApi.getBaseCurrency(accountId)).thenReturn("KRW");
        
        // Cache miss
        when(portfolioCachePort.getPortfolioCache(accountId))
            .thenReturn(Optional.empty()) // First check
            .thenReturn(Optional.empty()); // Double check

        when(portfolioCachePort.tryAcquireLock("lock:portfolio:" + accountId, 10)).thenReturn(true);
        
        when(accountApi.getBalances(accountId)).thenReturn(List.of(
            new AccountApi.AccountBalanceDto("BTC", new BigDecimal("1"), new BigDecimal("10000"), "KRW")
        ));

        when(exchangeRateProvider.getExchangeRates(List.of("BTC", "KRW"), "KRW"))
            .thenReturn(Map.of(
                "BTC", new ExchangeRateProvider.ExchangeRate(new BigDecimal("15000"), true),
                "KRW", new ExchangeRateProvider.ExchangeRate(BigDecimal.ONE, false)
            ));

        PortfolioSummaryResponse response = service.getPortfolioSummary(accountId);

        assertThat(response.accountId()).isEqualTo(accountId);
        assertThat(response.totalAssetValue()).isEqualByComparingTo("15000");
        assertThat(response.isStaleData()).isTrue();
        
        verify(portfolioCachePort).savePortfolioCache(eq(accountId), any(PortfolioCacheDto.class));
        verify(portfolioCachePort).releaseLock(anyString());
    }

    @Test
    void getPortfolioSummary_should_fall_back_to_db_if_lock_acquisition_fails() {
        UUID accountId = UUID.randomUUID();
        when(accountApi.getBaseCurrency(accountId)).thenReturn("KRW");
        when(portfolioCachePort.getPortfolioCache(accountId)).thenReturn(Optional.empty());

        when(portfolioCachePort.tryAcquireLock("lock:portfolio:" + accountId, 10)).thenReturn(false);
        when(accountApi.getBalances(accountId)).thenReturn(java.util.List.of());

        // 락 획득 실패는 캐시 스탬피드 방어 실패일 뿐이다. 조회 자체는 DB 로 폴백해 성공해야 한다.
        // 예전처럼 예외를 던지면 락 경합만으로 조회 API 가 500 을 반환한다.
        var response = service.getPortfolioSummary(accountId);

        assertThat(response.accountId()).isEqualTo(accountId);
        assertThat(response.assets()).isEmpty();
        verify(accountApi).getBalances(accountId);
    }

    @Test
    void getPortfolioSummary_should_skip_asset_if_rate_missing() {
        UUID accountId = UUID.randomUUID();
        when(accountApi.getBaseCurrency(accountId)).thenReturn("KRW");
        
        PortfolioCacheDto.AssetBalance balance = new PortfolioCacheDto.AssetBalance("BTC", new BigDecimal("1"), new BigDecimal("10000"), "KRW");
        PortfolioCacheDto cacheDto = new PortfolioCacheDto(accountId, "KRW", List.of(balance));
        when(portfolioCachePort.getPortfolioCache(accountId)).thenReturn(Optional.of(cacheDto));

        when(exchangeRateProvider.getExchangeRates(List.of("BTC", "KRW"), "KRW"))
            .thenReturn(Collections.emptyMap()); // Missing rates

        PortfolioSummaryResponse response = service.getPortfolioSummary(accountId);

        assertThat(response.assets()).isEmpty();
        assertThat(response.totalAssetValue()).isEqualByComparingTo("0");
    }

    @Test
    void getPortfolioSummary_should_skip_asset_if_quote_rate_missing() {
        UUID accountId = UUID.randomUUID();
        when(accountApi.getBaseCurrency(accountId)).thenReturn("KRW");
        
        PortfolioCacheDto.AssetBalance balance = new PortfolioCacheDto.AssetBalance("BTC", new BigDecimal("1"), new BigDecimal("10000"), "KRW");
        PortfolioCacheDto cacheDto = new PortfolioCacheDto(accountId, "KRW", List.of(balance));
        when(portfolioCachePort.getPortfolioCache(accountId)).thenReturn(Optional.of(cacheDto));

        when(exchangeRateProvider.getExchangeRates(List.of("BTC", "KRW"), "KRW"))
            .thenReturn(Map.of("BTC", new ExchangeRateProvider.ExchangeRate(new BigDecimal("15000"), false))); // KRW is missing

        PortfolioSummaryResponse response = service.getPortfolioSummary(accountId);

        assertThat(response.assets()).isEmpty();
        assertThat(response.totalAssetValue()).isEqualByComparingTo("0");
    }

    @Test
    void getPortfolioSummary_should_fall_back_to_db_on_interruption_during_lock() {
        UUID accountId = UUID.randomUUID();
        when(accountApi.getBaseCurrency(accountId)).thenReturn("KRW");
        when(portfolioCachePort.getPortfolioCache(accountId)).thenReturn(Optional.empty());

        when(portfolioCachePort.tryAcquireLock("lock:portfolio:" + accountId, 10)).thenReturn(false);
        when(accountApi.getBalances(accountId)).thenReturn(java.util.List.of());

        Thread.currentThread().interrupt();

        // 인터럽트가 걸려도 조회는 DB 폴백으로 완주하고, 인터럽트 상태는 보존되어야 한다.
        var response = service.getPortfolioSummary(accountId);
        assertThat(response.accountId()).isEqualTo(accountId);
        assertThat(Thread.currentThread().isInterrupted()).isTrue();

        Thread.interrupted(); // Clear interrupt flag
    }

    @Test
    void getPortfolioSummary_should_use_cache_on_double_check() {
        UUID accountId = UUID.randomUUID();
        when(accountApi.getBaseCurrency(accountId)).thenReturn("KRW");
        
        PortfolioCacheDto.AssetBalance balance = new PortfolioCacheDto.AssetBalance("BTC", new BigDecimal("1"), new BigDecimal("10000"), "KRW");
        PortfolioCacheDto cacheDto = new PortfolioCacheDto(accountId, "KRW", List.of(balance));
        
        // Cache miss on first check, cache hit on second check
        when(portfolioCachePort.getPortfolioCache(accountId))
            .thenReturn(Optional.empty()) 
            .thenReturn(Optional.of(cacheDto)); 

        when(portfolioCachePort.tryAcquireLock("lock:portfolio:" + accountId, 10)).thenReturn(true);

        when(exchangeRateProvider.getExchangeRates(List.of("BTC", "KRW"), "KRW"))
            .thenReturn(Map.of(
                "BTC", new ExchangeRateProvider.ExchangeRate(new BigDecimal("15000"), false),
                "KRW", new ExchangeRateProvider.ExchangeRate(BigDecimal.ONE, false)
            ));

        PortfolioSummaryResponse response = service.getPortfolioSummary(accountId);

        assertThat(response.assets()).hasSize(1);
        verify(accountApi, never()).getBalances(any());
        verify(portfolioCachePort).releaseLock("lock:portfolio:" + accountId);
    }
}
