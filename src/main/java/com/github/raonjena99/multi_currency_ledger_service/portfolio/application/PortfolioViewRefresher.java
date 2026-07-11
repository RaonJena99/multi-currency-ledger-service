package com.github.raonjena99.multi_currency_ledger_service.portfolio.application;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import io.micrometer.core.annotation.Timed;

import com.github.raonjena99.multi_currency_ledger_service.account.domain.event.TradeExecutedEvent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 포트폴리오 구체화된 뷰(Materialized View)의 갱신을 담당하는 PortfolioViewRefresher(포트폴리오 뷰 갱신) 클래스입니다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PortfolioViewRefresher {

    private final JdbcTemplate jdbcTemplate;

    /**
     * Account 모듈에서 발생한 TradeExecutedEvent(거래 실행 이벤트)를 구독하여 포트폴리오 뷰를 비동기적으로 갱신합니다.
     * @param event 거래 실행 이벤트 객체
     */
    @Async
    @Timed(value = "portfolio.view.refresh.time", description = "Time taken to refresh portfolio materialized view")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleTradeExecuted(TradeExecutedEvent event) {
        log.debug("Trade committed (TradeID: {}). Portfolio module is refreshing materialized view...", event.tradeId());
        
        try {
            jdbcTemplate.execute("REFRESH MATERIALIZED VIEW CONCURRENTLY current_portfolio_view");
        } catch (Exception e) {
            log.error("Portfolio materialized view refresh failed.", e);
        }
    }
}
