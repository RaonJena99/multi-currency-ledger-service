package com.github.raonjena99.multi_currency_ledger_service.portfolio.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.github.raonjena99.multi_currency_ledger_service.account.AccountApi;
import com.github.raonjena99.multi_currency_ledger_service.account.domain.event.TradeExecutedEvent;
import com.github.raonjena99.multi_currency_ledger_service.common.model.AssetType;
import com.github.raonjena99.multi_currency_ledger_service.common.model.TradeType;
import com.github.raonjena99.multi_currency_ledger_service.portfolio.application.dto.PortfolioCacheDto;
import com.github.raonjena99.multi_currency_ledger_service.portfolio.application.port.PortfolioCachePort;

@ExtendWith(MockitoExtension.class)
class PortfolioViewRefresherTest {

    @Mock private PortfolioCachePort portfolioCachePort;
    @Mock private AccountApi accountApi;

    @InjectMocks
    private PortfolioViewRefresher refresher;

    @Test
    void updateRedisCache_should_update_cache_successfully() {
        UUID accountId = UUID.randomUUID();
        UUID tradeId = UUID.randomUUID();
        TradeExecutedEvent event = new TradeExecutedEvent(
            tradeId, accountId, "BTC", AssetType.CRYPTO, "KRW", "USD", TradeType.BUY, 
            BigDecimal.ONE, new BigDecimal("10000"), BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO, false, OffsetDateTime.now()
        );

        when(portfolioCachePort.tryAcquireLock(anyString(), anyLong())).thenReturn(true);
        when(accountApi.getBalances(accountId)).thenReturn(List.of(
            new AccountApi.AccountBalanceDto("BTC", BigDecimal.ONE, new BigDecimal("10000"), "KRW")
        ));
        when(accountApi.getBaseCurrency(accountId)).thenReturn("USD");

        refresher.updateRedisCache(event);

        verify(portfolioCachePort).savePortfolioCache(eq(accountId), any(PortfolioCacheDto.class));
        verify(portfolioCachePort).releaseLock(anyString());
    }

    @Test
    void updateRedisCache_should_evict_cache_if_api_fails() {
        UUID accountId = UUID.randomUUID();
        UUID tradeId = UUID.randomUUID();
        TradeExecutedEvent event = new TradeExecutedEvent(
            tradeId, accountId, "BTC", AssetType.CRYPTO, "KRW", "USD", TradeType.BUY, 
            BigDecimal.ONE, new BigDecimal("10000"), BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO, false, OffsetDateTime.now()
        );

        when(portfolioCachePort.tryAcquireLock(anyString(), anyLong())).thenReturn(true);
        when(accountApi.getBalances(accountId)).thenThrow(new RuntimeException("API failure"));

        refresher.updateRedisCache(event);

        verify(portfolioCachePort).evictPortfolioCache(accountId);
        verify(portfolioCachePort).releaseLock(anyString());
    }

    @Test
    void updateRedisCache_should_skip_if_lock_fails() {
        UUID accountId = UUID.randomUUID();
        UUID tradeId = UUID.randomUUID();
        TradeExecutedEvent event = new TradeExecutedEvent(
            tradeId, accountId, "BTC", AssetType.CRYPTO, "KRW", "USD", TradeType.BUY, 
            BigDecimal.ONE, new BigDecimal("10000"), BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO, false, OffsetDateTime.now()
        );

        when(portfolioCachePort.tryAcquireLock(anyString(), anyLong())).thenReturn(false);

        refresher.updateRedisCache(event);

        verify(accountApi, never()).getBalances(any());
        verify(portfolioCachePort, never()).savePortfolioCache(any(), any());
    }

    @Test
    void updateRedisCache_should_return_on_interruption_during_lock() {
        UUID accountId = UUID.randomUUID();
        UUID tradeId = UUID.randomUUID();
        TradeExecutedEvent event = new TradeExecutedEvent(
            tradeId, accountId, "BTC", AssetType.CRYPTO, "KRW", "USD", TradeType.BUY, 
            BigDecimal.ONE, new BigDecimal("10000"), BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO, false, OffsetDateTime.now()
        );

        when(portfolioCachePort.tryAcquireLock(anyString(), anyLong())).thenReturn(false);

        Thread.currentThread().interrupt();

        refresher.updateRedisCache(event);

        verify(accountApi, never()).getBalances(any());
        verify(portfolioCachePort, never()).savePortfolioCache(any(), any());
        
        Thread.interrupted(); // Clear interrupt flag
    }
}
