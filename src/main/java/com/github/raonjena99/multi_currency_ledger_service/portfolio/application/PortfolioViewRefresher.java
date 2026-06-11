package com.github.raonjena99.multi_currency_ledger_service.portfolio.application;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.github.raonjena99.multi_currency_ledger_service.account.domain.event.TradeExecutedEvent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class PortfolioViewRefresher {

    private final JdbcTemplate jdbcTemplate;

    // Account 모듈에서 발생한 이벤트를 Portfolio 모듈이 구독(Subscribe)
    @Async
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
