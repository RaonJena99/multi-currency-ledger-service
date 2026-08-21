package com.github.raonjena99.multi_currency_ledger_service.portfolio.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.github.raonjena99.multi_currency_ledger_service.account.AccountApi;
import com.github.raonjena99.multi_currency_ledger_service.account.AccountApi.AccountBalanceDto;
import com.github.raonjena99.multi_currency_ledger_service.account.domain.event.TradeExecutedEvent;
import com.github.raonjena99.multi_currency_ledger_service.portfolio.application.dto.PortfolioCacheDto;
import com.github.raonjena99.multi_currency_ledger_service.portfolio.application.port.PortfolioCachePort;

@ExtendWith(MockitoExtension.class)
@DisplayName("인프라 단위 테스트: PortfolioViewRefresher (Redis Write-Through 갱신 검증)")
class PortfolioViewRefresherTest {

    @Mock
    private PortfolioCachePort portfolioCachePort;

    @Mock
    private AccountApi accountApi;

    @InjectMocks
    private PortfolioViewRefresher portfolioViewRefresher;

    @Test
    @DisplayName("이벤트 수신 시 AccountApi를 통해 잔고를 조회하고 PortfolioCachePort로 캐시를 갱신한다.")
    void update_redis_cache_success() {
        // given
        UUID accountId = UUID.randomUUID();
        TradeExecutedEvent mockEvent = new TradeExecutedEvent(
            UUID.randomUUID(), accountId, "BTC", com.github.raonjena99.multi_currency_ledger_service.common.model.AssetType.CRYPTO, "KRW", "KRW", com.github.raonjena99.multi_currency_ledger_service.common.model.TradeType.BUY,
            new BigDecimal("1"), new BigDecimal("50000000"), BigDecimal.ONE, BigDecimal.ZERO,
            false, java.time.OffsetDateTime.now()
        );

        when(accountApi.getBaseCurrency(accountId)).thenReturn("KRW");
        when(accountApi.getBalances(accountId)).thenReturn(List.of(
            new AccountBalanceDto("BTC", new BigDecimal("1"), new BigDecimal("50000000"), "KRW")
        ));
        when(portfolioCachePort.tryAcquireLock(any(), org.mockito.ArgumentMatchers.anyLong())).thenReturn(true);

        // when
        portfolioViewRefresher.updateRedisCache(mockEvent);

        // then
        verify(accountApi).getBaseCurrency(accountId);
        verify(accountApi).getBalances(accountId);
        verify(portfolioCachePort).savePortfolioCache(eq(accountId), any(PortfolioCacheDto.class));
    }

    @Test
    @DisplayName("캐시 갱신 실패 시 기존 캐시를 유지하여 Eviction Storm을 방지하고 락을 해제한다.")
    void update_redis_cache_failure() {
        // given
        UUID accountId = UUID.randomUUID();
        TradeExecutedEvent mockEvent = new TradeExecutedEvent(
            UUID.randomUUID(), accountId, "BTC", com.github.raonjena99.multi_currency_ledger_service.common.model.AssetType.CRYPTO, "KRW", "KRW", com.github.raonjena99.multi_currency_ledger_service.common.model.TradeType.BUY,
            new BigDecimal("1"), new BigDecimal("50000000"), BigDecimal.ONE, BigDecimal.ZERO,
            false, java.time.OffsetDateTime.now()
        );

        when(portfolioCachePort.tryAcquireLock(any(), org.mockito.ArgumentMatchers.anyLong())).thenReturn(true);
        when(accountApi.getBalances(accountId)).thenThrow(new RuntimeException("API Error"));

        // when
        portfolioViewRefresher.updateRedisCache(mockEvent);

        // then
        verify(portfolioCachePort).releaseLock(any());
    }
}
