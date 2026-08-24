package com.github.raonjena99.multi_currency_ledger_service.reconciliation.infrastructure.scheduler;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

/**
 * 월간 대사 배치의 실행 트리거입니다.
 *
 * <p>{@code spring.batch.job.enabled: false} 이므로 애플리케이션 시작 시 자동 실행되지 않습니다.
 * 트리거가 없으면 잡 정의가 존재해도 프로덕션에서 한 번도 돌지 않습니다.
 * ShedLock 으로 다중 노드 중 한 대에서만 실행되도록 보장합니다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReconciliationJobScheduler {

    private final JobOperator jobOperator;

    @Qualifier("monthlyReconciliationJob")
    private final Job monthlyReconciliationJob;

    /**
     * 매월 1일 04시에 전월 정산 대사를 실행합니다.
     */
    @Scheduled(cron = "${ledger.reconciliation.cron:0 0 4 1 * *}")
    @SchedulerLock(name = "monthly_reconciliation_job", lockAtLeastFor = "PT1M", lockAtMostFor = "PT6H")
    public void runMonthlyReconciliation() {
        OffsetDateTime startOfPreviousMonth = OffsetDateTime.now(ZoneOffset.UTC)
                .truncatedTo(ChronoUnit.DAYS)
                .withDayOfMonth(1)
                .minusMonths(1);

        launch(startOfPreviousMonth);
    }

    /**
     * 지정한 월을 대상으로 대사 배치를 실행합니다. 운영 중 수동 재실행에도 사용합니다.
     *
     * @param startOfMonth 대상 월의 1일 00:00 (UTC)
     */
    public void launch(OffsetDateTime startOfMonth) {
        JobParameters parameters = new JobParametersBuilder()
                .addString("startOfMonth", startOfMonth.toString())
                // 같은 월을 재실행할 수 있도록 실행 식별자를 분리한다.
                .addString("launchedAt", OffsetDateTime.now(ZoneOffset.UTC).toString())
                .toJobParameters();

        try {
            log.info("월간 대사 배치를 시작합니다. 대상 월 = {}", startOfMonth);
            var execution = jobOperator.start(monthlyReconciliationJob, parameters);
            log.info("월간 대사 배치 종료. status={}, exitStatus={}",
                    execution.getStatus(), execution.getExitStatus().getExitCode());
        } catch (Exception e) {
            log.error("월간 대사 배치 실행에 실패했습니다. 대상 월 = {}", startOfMonth, e);
        }
    }
}
