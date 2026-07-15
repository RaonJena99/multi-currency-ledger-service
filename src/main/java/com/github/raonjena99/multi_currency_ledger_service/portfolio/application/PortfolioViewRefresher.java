package com.github.raonjena99.multi_currency_ledger_service.portfolio.application;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.github.raonjena99.multi_currency_ledger_service.account.domain.event.TradeExecutedEvent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

/**
 * 포트폴리오 구체화된 뷰(Materialized View)의 갱신을 담당하는 PortfolioViewRefresher(포트폴리오 뷰 갱신) 클래스입니다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PortfolioViewRefresher {

    private final JdbcTemplate jdbcTemplate;

    private final AtomicBoolean isDirty = new AtomicBoolean(false);

    private final StringRedisTemplate redisTemplate;

    /**
     * TradeExecutedEvent(거래 실행 이벤트)가 발생하면, 포트폴리오 뷰를 갱신해야 함을 나타내는 플래그를 설정합니다.
     * @param event 거래 실행 이벤트 객체
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void markAsDirty(TradeExecutedEvent event) {
        log.debug("Trade committed (TradeID: {}). Marked portfolio view as dirty.", event.tradeId());

        isDirty.set(true);

        String redisKey = "portfolio:dirty:" + event.accountId().toString();
        redisTemplate.opsForValue().set(redisKey, "true", Duration.ofSeconds(30));
    }

    /**
    * 주기적으로 실행되면서, 더티 플래그가 true인 경우에만 포트폴리오 뷰를 갱신합니다.
    */
    @SchedulerLock(name = "portfolio_view_refresh_lock", lockAtMostFor = "PT5S", lockAtLeastFor = "PT2S")
    @Scheduled(fixedDelay = 2000)
    public void scheduledRefresh(){
        try {
            jdbcTemplate.execute("REFRESH MATERIALIZED VIEW CONCURRENTLY current_portfolio_view");

            String refreshTimeStr = String.valueOf(System.currentTimeMillis());
            redisTemplate.opsForValue().set("portfolio:last_refresh_time", refreshTimeStr);
            
            log.info("Successfully refreshed portfolio view.");
        } catch (Exception e) {
            isDirty.set(true);
            
            log.error("Portfolio materialized view refresh failed.", e);
        }
    }
}
