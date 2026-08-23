package com.github.raonjena99.multi_currency_ledger_service.account.application;

import java.time.OffsetDateTime;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.github.raonjena99.multi_currency_ledger_service.account.infrastructure.IdempotencyRecordRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

@Slf4j
@Component
@RequiredArgsConstructor
public class IdempotencyCleanupWorker {
    private final IdempotencyRecordRepository idempotencyRepository;

    /**
     * 매일 새벽 3시에 실행되어 7일이 지난 멱등성 키를 삭제합니다.
     * ShedLock을 통해 다중 노드 중 1대의 서버에서만 실행되도록 보장합니다.
     */
    @Scheduled(cron = "0 0 3 * * *")
    @SchedulerLock(name = "idempotency_cleanup_task", lockAtLeastFor = "PT5M", lockAtMostFor = "PT30M")
    public void cleanupOldRecords() {
        OffsetDateTime threshold = OffsetDateTime.now().minusDays(7);
        
        int totalDeleted = 0;
        int deletedBatch;
        
        do {
            // 한 트랜잭션 당 1,000건씩만 깔끔하게 삭제 (테이블 락 방지)
            deletedBatch = idempotencyRepository.deleteByCreatedAtBeforeWithLimit(threshold, 1000);
            totalDeleted += deletedBatch;
        } while (deletedBatch == 1000);
        
        log.info("오래된 멱등성 키(Idempotency Record) {}건이 성공적으로 정리되었습니다. (기준: {})", totalDeleted, threshold);
    }
}
