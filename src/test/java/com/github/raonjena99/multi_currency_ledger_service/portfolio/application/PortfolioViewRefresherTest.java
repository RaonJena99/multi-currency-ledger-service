package com.github.raonjena99.multi_currency_ledger_service.portfolio.application;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import com.github.raonjena99.multi_currency_ledger_service.account.domain.event.TradeExecutedEvent;

@ExtendWith(MockitoExtension.class)
@DisplayName("인프라 단위 테스트: PortfolioViewRefresher (Materialized View 비동기 갱신 검증)")
class PortfolioViewRefresherTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @InjectMocks
    private PortfolioViewRefresher portfolioViewRefresher;

    @Test
    @DisplayName("이벤트 수신 시 더티 플래그를 설정하고, 스케줄러가 플래그를 감지하여 뷰를 갱신한다.")
    void trigger_materialized_view_refresh() {
        // given
        TradeExecutedEvent mockEvent = new TradeExecutedEvent(
            UUID.randomUUID(), UUID.randomUUID(), "BTC", com.github.raonjena99.multi_currency_ledger_service.common.model.AssetType.CRYPTO, "KRW", com.github.raonjena99.multi_currency_ledger_service.common.model.TradeType.BUY,
            new BigDecimal("1"), new BigDecimal("50000000"), BigDecimal.ONE, BigDecimal.ZERO,
            false, java.time.OffsetDateTime.now()
        );

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        // 스케줄러 실행 검증 (쿼리 실행 및 Redis 시간 갱신됨)
        portfolioViewRefresher.scheduledRefresh();
        verify(jdbcTemplate).execute("REFRESH MATERIALIZED VIEW CONCURRENTLY current_portfolio_view");
        verify(valueOperations).set(org.mockito.ArgumentMatchers.eq("portfolio:last_refresh_time"), org.mockito.ArgumentMatchers.anyString());

        // when (이벤트 수신 -> 더티 플래그 ON 및 Redis dirty 30초 세팅)
        portfolioViewRefresher.markAsDirty(mockEvent);

        verify(valueOperations).set("portfolio:dirty:" + mockEvent.accountId().toString(), "true", java.time.Duration.ofSeconds(30));

        // then (다시 스케줄러 실행 -> 쿼리 1번 더 실행)
        portfolioViewRefresher.scheduledRefresh();
        verify(jdbcTemplate, org.mockito.Mockito.times(2)).execute("REFRESH MATERIALIZED VIEW CONCURRENTLY current_portfolio_view");
    }
}
