package com.github.raonjena99.multi_currency_ledger_service.portfolio.application;

import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import com.github.raonjena99.multi_currency_ledger_service.account.domain.event.TradeExecutedEvent;

@ExtendWith(MockitoExtension.class)
@DisplayName("인프라 단위 테스트: PortfolioViewRefresher (Materialized View 비동기 갱신 검증)")
class PortfolioViewRefresherTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

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

        // 플래그 설정 안된 상태에서의 스케줄러 실행 검증 (쿼리 실행 안 됨)
        portfolioViewRefresher.scheduledRefresh();
        org.mockito.Mockito.verifyNoInteractions(jdbcTemplate);

        // when (이벤트 수신 -> 더티 플래그 ON)
        portfolioViewRefresher.markAsDirty(mockEvent);

        // then (스케줄러 실행 -> 플래그 감지 후 쿼리 실행)
        portfolioViewRefresher.scheduledRefresh();
        verify(jdbcTemplate).execute("REFRESH MATERIALIZED VIEW CONCURRENTLY current_portfolio_view");

        // 다시 스케줄러 실행 -> 플래그 OFF 상태이므로 쿼리 추가 실행 안 됨 (호출 횟수 1 유지)
        portfolioViewRefresher.scheduledRefresh();
        verify(jdbcTemplate, org.mockito.Mockito.times(1)).execute("REFRESH MATERIALIZED VIEW CONCURRENTLY current_portfolio_view");
    }
}
