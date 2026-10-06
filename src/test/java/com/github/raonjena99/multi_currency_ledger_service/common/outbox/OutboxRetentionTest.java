package com.github.raonjena99.multi_currency_ledger_service.common.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.github.raonjena99.multi_currency_ledger_service.IntegrationTestSupport;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * 발행이 끝난 아웃박스 이벤트를 보존 기간이 지나면 지우는지, 지우면 안 되는 행은 남기는지 검증합니다.
 *
 * <p>미처리 행은 정합성 점검과 기초 잔고 마이그레이션이 "발행 대기"를 판단하는 근거이고,
 * 데드레터 행은 관리자 재발행 대상입니다. 둘은 기간과 관계없이 남아야 합니다.
 */
@DisplayName("아웃박스 보존 기간 정리")
class OutboxRetentionTest extends IntegrationTestSupport {

    @Autowired private OutboxRetentionWorker retentionWorker;
    @Autowired private OutboxRepository outboxRepository;
    @Autowired private MeterRegistry meterRegistry;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private org.springframework.transaction.support.TransactionTemplate txTemplate;

    @AfterEach
    void tearDown() {
        jdbcTemplate.execute("TRUNCATE TABLE outbox_events");
    }

    /**
     * 이벤트를 만든다. 릴레이가 집어 가지 않도록 다음 시도를 미래로 미룬다.
     *
     * @param processedDaysAgo 발행된 지 며칠 지났는지. null 이면 미처리
     */
    private Long event(Integer processedDaysAgo, boolean deadLetter) {
        Long id = outboxRepository.save(new OutboxEvent("Ledger", "acc", "LedgerRecordingCommand", "{}", null)).getId();
        jdbcTemplate.update("UPDATE outbox_events SET next_attempt_at = now() + interval '1 day', dead_letter = ?, "
                + "created_at = now() - interval '30 days' WHERE id = ?", deadLetter, id);
        if (processedDaysAgo != null) {
            jdbcTemplate.update("UPDATE outbox_events SET processed = true, processed_at = now() - make_interval(days => ?) "
                    + "WHERE id = ?", processedDaysAgo, id);
        }
        return id;
    }

    private List<Long> remainingIds() {
        return jdbcTemplate.queryForList("SELECT id FROM outbox_events ORDER BY id", Long.class);
    }

    @Test
    @DisplayName("발행 완료로 표시할 때 발행 시각을 남긴다")
    void markAsProcessedRecordsProcessedAt() {
        Long id = event(null, false);
        OffsetDateTime before = OffsetDateTime.now().minusSeconds(5);

        // 운영에서는 OutboxManager 의 트랜잭션 안에서 호출된다.
        txTemplate.executeWithoutResult(status -> outboxRepository.markAsProcessedInBatch(List.of(id)));

        OffsetDateTime processedAt = jdbcTemplate.queryForObject(
                "SELECT processed_at FROM outbox_events WHERE id = ?", OffsetDateTime.class, id);
        assertThat(processedAt).isAfter(before);
    }

    @Test
    @DisplayName("보존 기간이 지난 발행 완료 행만 지우고, 미처리·데드레터·최근 발행 행은 남긴다")
    void deletesOnlyExpiredProcessedEvents() {
        Long expired = event(8, false);
        Long recentlyProcessed = event(1, false);
        Long unprocessed = event(null, false);
        Long deadLetter = event(null, true);

        int deleted = retentionWorker.purgeProcessedEvents();

        assertThat(deleted).isEqualTo(1);
        assertThat(remainingIds()).doesNotContain(expired)
                .containsExactlyInAnyOrder(recentlyProcessed, unprocessed, deadLetter);
    }

    @Test
    @DisplayName("오래전에 만들어졌어도 최근에 발행된 행은 보존 기간 동안 남는다")
    void keepsLatePublishedEvents() {
        // 30일 전에 만들어져 재시도·재발행 끝에 어제 발행된 행. 만든 시각 기준이면 바로 지워진다.
        Long latePublished = event(1, false);

        retentionWorker.purgeProcessedEvents();

        assertThat(remainingIds()).containsExactly(latePublished);
    }

    @Test
    @DisplayName("한 번에 지우는 양을 나눠 끝까지 지운다")
    void deletesInBatches() {
        for (int i = 0; i < 5; i++) {
            event(10, false);
        }
        OutboxRetentionWorker smallBatches = new OutboxRetentionWorker(jdbcTemplate, new SimpleMeterRegistry(), 7, 2);

        int deleted = smallBatches.purgeProcessedEvents();

        assertThat(deleted).isEqualTo(5);
        assertThat(remainingIds()).isEmpty();
    }

    @Test
    @DisplayName("지운 건수를 지표로 남긴다")
    void countsDeletedEvents() {
        double before = meterRegistry.get("outbox.retention.deleted").counter().count();
        event(9, false);
        event(9, false);

        retentionWorker.purgeProcessedEvents();

        assertThat(meterRegistry.get("outbox.retention.deleted").counter().count()).isEqualTo(before + 2);
    }
}
