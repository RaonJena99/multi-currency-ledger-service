package com.github.raonjena99.multi_currency_ledger_service.portfolio.application;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.github.raonjena99.multi_currency_ledger_service.portfolio.infrastructure.PortfolioQueryRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

@Slf4j
@Component
@RequiredArgsConstructor
public class PortfolioViewRefreshWorker {
    
    private final PortfolioQueryRepository portfolioQueryRepository;

    @Scheduled(fixedDelay = 60000) // 1분마다 실행
    @SchedulerLock(name = "portfolio_view_refresh_task", lockAtLeastFor = "PT30S", lockAtMostFor = "PT2M")
    @Transactional
    public void refreshPortfolioView() {
        try {
            portfolioQueryRepository.refreshMaterializedView();
            log.debug("구체화된 뷰(current_portfolio_view)가 성공적으로 갱신되었습니다.");
        } catch (Exception e) {
            log.error("구체화된 뷰(current_portfolio_view) 갱신 중 오류가 발생했습니다.", e);
        }
    }
}