package com.github.raonjena99.multi_currency_ledger_service.portfolio.application;

import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
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

    private final AtomicBoolean isDirty = new AtomicBoolean(false);

    /**
     * TradeExecutedEvent(거래 실행 이벤트)가 발생하면, 포트폴리오 뷰를 갱신해야 함을 나타내는 플래그를 설정합니다.
     * @param event 거래 실행 이벤트 객체
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void markAsDirty(TradeExecutedEvent event) {
        log.debug("Trade committed (TradeID: {}). Marked portfolio view as dirty.", event.tradeId());
        isDirty.set(true);
    }

    /**
    * 주기적으로 실행되면서, 더티 플래그가 true인 경우에만 포트폴리오 뷰를 갱신합니다.
    */
    @Timed(value = "portfolio.view.refresh.time", description = "Time taken to refresh portfolio materialized  view")
    @Scheduled(fixedDelay = 10000)
    public void scheduledRefresh(){
        if (isDirty.compareAndSet(true,false)){
            log.info("Starting scheduled refresh of portfolio materialized view...");
            try {
                jdbcTemplate.execute("REFRESH MATERIALIZED VIEW CONCURRENTLY current_portfolio_view");
                log.info("Successfully refreshed portfolio view.");
            } catch (Exception e) {
                isDirty.set(true);
                log.error("Portfolio materialized view refresh failed.", e);
            }
        }
    }
}
