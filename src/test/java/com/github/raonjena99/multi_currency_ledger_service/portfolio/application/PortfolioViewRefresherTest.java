package com.github.raonjena99.multi_currency_ledger_service.portfolio.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import com.github.raonjena99.multi_currency_ledger_service.account.AccountApi;
import com.github.raonjena99.multi_currency_ledger_service.account.domain.event.TradeExecutedEvent;
import com.github.raonjena99.multi_currency_ledger_service.common.model.AssetType;
import com.github.raonjena99.multi_currency_ledger_service.common.model.TradeType;

@ExtendWith(MockitoExtension.class)
@DisplayName("인프라 단위 테스트: PortfolioViewRefresher")
class PortfolioViewRefresherTest {

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    @Mock
    private ValueOperations<String, Object> valueOperations;

    @Mock
    private AccountApi accountApi;

    @InjectMocks
    private PortfolioViewRefresher portfolioViewRefresher;

    @Test
    @DisplayName("이벤트 수신 시 Redis 캐시를 업데이트한다.")
    void updateRedisCache() {
        // given
        TradeExecutedEvent mockEvent = new TradeExecutedEvent(
            UUID.randomUUID(), UUID.randomUUID(), "BTC", AssetType.CRYPTO, "KRW", "KRW", TradeType.BUY,
            new BigDecimal("1"), new BigDecimal("50000000"), BigDecimal.ONE, BigDecimal.ZERO,
            false, java.time.OffsetDateTime.now()
        );

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), any())).thenReturn(true);
        when(accountApi.getBalances(any())).thenReturn(List.of());
        when(accountApi.getBaseCurrency(any())).thenReturn("KRW");

        // when
        portfolioViewRefresher.updateRedisCache(mockEvent);

        // then
        verify(accountApi).getBalances(mockEvent.accountId());
        verify(valueOperations).set(anyString(), any(), any());
        verify(redisTemplate).delete(anyString());
    }
}
