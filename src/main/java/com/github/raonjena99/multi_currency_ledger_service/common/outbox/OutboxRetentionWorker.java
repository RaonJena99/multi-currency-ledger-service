package com.github.raonjena99.multi_currency_ledger_service.common.outbox;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

/**
 * 발행이 끝나고 보존 기간이 지난 아웃박스 이벤트를 지웁니다.
 *
 * <p>거래 1건마다 페이로드(JSON) 1행이 생기므로, 지우지 않으면 저장 공간·백업·VACUUM 비용이 계속 늡니다.
 *
 * <p><b>지우지 않는 행</b>
 * <ul>
 *   <li>미처리 행: 정합성 점검과 기초 잔고 마이그레이션이 "발행 대기"를 판단하는 근거입니다.</li>
 *   <li>데드레터 행: 관리자 재발행 대상입니다.</li>
 * </ul>
 * 기준은 만든 시각이 아니라 발행 시각({@code processed_at})입니다. 재시도·재발행 끝에 늦게 발행된 행도
 * 보존 기간 동안 남아 장애를 조사할 수 있습니다.
 *
 * <p>한 번에 지우는 양을 제한하고 묶음마다 따로 커밋합니다. 쌓인 양이 많아도 긴 트랜잭션이나 테이블 잠금을
 * 만들지 않습니다.
 */
@Slf4j
@Component
public class OutboxRetentionWorker {

    private static final String DELETE_BATCH = """
            DELETE FROM outbox_events
            WHERE id IN (
                SELECT id FROM outbox_events
                WHERE processed = true AND dead_letter = false
                  AND processed_at < now() - make_interval(days => ?)
                ORDER BY id
                LIMIT ?
            )
            """;

    private final JdbcTemplate jdbcTemplate;
    private final Counter deletedCounter;
    private final int retentionDays;
    private final int batchSize;

    public OutboxRetentionWorker(JdbcTemplate jdbcTemplate, MeterRegistry meterRegistry,
                                 @Value("${ledger.outbox.retention-days:7}") int retentionDays,
                                 @Value("${ledger.outbox.retention-batch-size:1000}") int batchSize) {
        this.jdbcTemplate = jdbcTemplate;
        this.retentionDays = retentionDays;
        this.batchSize = batchSize;
        this.deletedCounter = Counter.builder("outbox.retention.deleted")
                .description("보존 기간이 지나 삭제한 발행 완료 아웃박스 이벤트 수")
                .register(meterRegistry);
    }

    @Scheduled(cron = "${ledger.outbox.retention-cron:0 15 3 * * *}", zone = "UTC")
    @SchedulerLock(name = "outbox_retention", lockAtLeastFor = "PT1M", lockAtMostFor = "PT30M")
    public void scheduledPurge() {
        try {
            purgeProcessedEvents();
        } catch (Exception e) {
            log.error("아웃박스 보존 기간 정리에 실패했습니다.", e);
        }
    }

    /**
     * 보존 기간이 지난 발행 완료 이벤트를 모두 지웁니다.
     *
     * @return 지운 행 수
     */
    public int purgeProcessedEvents() {
        int total = 0;
        int deleted;
        do {
            // JdbcTemplate 호출마다 커밋된다(트랜잭션 밖). 묶음 하나가 하나의 짧은 트랜잭션이다.
            deleted = jdbcTemplate.update(DELETE_BATCH, retentionDays, batchSize);
            total += deleted;
        } while (deleted == batchSize);

        deletedCounter.increment(total);
        if (total > 0) {
            log.info("보존 기간({}일)이 지난 발행 완료 아웃박스 이벤트 {}건을 삭제했습니다.", retentionDays, total);
        }
        return total;
    }
}
