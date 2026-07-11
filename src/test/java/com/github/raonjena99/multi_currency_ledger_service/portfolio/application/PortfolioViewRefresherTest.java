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
    @DisplayName("트랜잭션 커밋 직후 TradeExecutedEvent가 수신되면 Lock-free 방식으로 뷰를 갱신한다.")
    void trigger_materialized_view_refresh() {
        // given
        TradeExecutedEvent mockEvent = new TradeExecutedEvent(
            UUID.randomUUID(), UUID.randomUUID(), "BTC", com.github.raonjena99.multi_currency_ledger_service.common.model.AssetType.CRYPTO, "KRW", com.github.raonjena99.multi_currency_ledger_service.common.model.TradeType.BUY,
            new BigDecimal("1"), new BigDecimal("50000000"), BigDecimal.ONE, BigDecimal.ZERO,
            false, java.time.OffsetDateTime.now()
        );

        // when
        portfolioViewRefresher.handleTradeExecuted(mockEvent);

        // then
        // 백그라운드 스레드에서 정확히 CONCURRENTLY 옵션을 포함한 리프레시 쿼리가 실행되었는지 검증
        verify(jdbcTemplate).execute("REFRESH MATERIALIZED VIEW CONCURRENTLY current_portfolio_view");
    }
}
