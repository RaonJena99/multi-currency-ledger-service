package com.github.raonjena99.multi_currency_ledger_service.transaction.infrastructure.scheduler;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.github.raonjena99.multi_currency_ledger_service.transaction.application.LedgerIntegrityCheckService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

/**
 * 원장 정합성 점검을 매일 실행합니다. 여러 노드에서 동시에 실행되지 않도록 ShedLock 으로 잠급니다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LedgerIntegrityCheckScheduler {

    private final LedgerIntegrityCheckService checkService;

    @Scheduled(cron = "${ledger.integrity.cron:0 30 3 * * *}", zone = "UTC")
    @SchedulerLock(name = "ledger_integrity_check", lockAtLeastFor = "PT5M", lockAtMostFor = "PT30M")
    public void runDailyCheck() {
        try {
            checkService.runCheck();
        } catch (Exception e) {
            // 점검 실패는 결과가 남지 않으므로 Gauge 가 직전 값에 머문다. 로그로 남겨 알 수 있게 한다.
            log.error("원장 정합성 점검 실행에 실패했습니다.", e);
        }
        try {
            checkService.purgeOldRuns();
        } catch (Exception e) {
            log.error("원장 정합성 점검 결과 정리에 실패했습니다.", e);
        }
    }
}
