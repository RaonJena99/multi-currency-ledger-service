package com.github.raonjena99.multi_currency_ledger_service.common.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.github.raonjena99.multi_currency_ledger_service.IntegrationTestSupport;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * 아웃박스 데드레터 건수 지표를 실제 DB 로 검증합니다.
 *
 * <p>데드레터는 릴레이가 영구히 건너뛰므로, 지표가 없으면 관리자 API 를 직접 조회하기 전에는
 * 원장 이벤트가 발행되지 못하고 있다는 사실을 알 수 없습니다.
 */
@DisplayName("아웃박스 데드레터 지표")
class OutboxDeadLetterMetricsTest extends IntegrationTestSupport {

    @Autowired private OutboxDeadLetterMetrics metrics;
    @Autowired private OutboxRepository outboxRepository;
    @Autowired private MeterRegistry meterRegistry;
    @Autowired private JdbcTemplate jdbcTemplate;

    @AfterEach
    void tearDown() {
        jdbcTemplate.execute("TRUNCATE TABLE outbox_events");
    }

    private Long outbox(boolean deadLetter) {
        Long id = outboxRepository.save(new OutboxEvent("Ledger", "acc", "LedgerRecordingCommand", "{}", null)).getId();
        // 릴레이가 집어 가지 않도록 다음 시도를 미래로 미룬다.
        jdbcTemplate.update("UPDATE outbox_events SET dead_letter = ?, next_attempt_at = now() + interval '1 day' "
                + "WHERE id = ?", deadLetter, id);
        return id;
    }

    private double gauge() {
        return meterRegistry.get("outbox.dead_letters").gauge().value();
    }

    @Test
    @DisplayName("데드레터 건수만 세고, 재발행하면 줄어든다")
    void countsDeadLettersAndDropsAfterRequeue() {
        Long first = outbox(true);
        outbox(true);
        outbox(false);

        metrics.refresh();
        assertThat(gauge()).isEqualTo(2.0);

        jdbcTemplate.update("UPDATE outbox_events SET dead_letter = false WHERE id = ?", first);
        metrics.refresh();
        assertThat(gauge()).isEqualTo(1.0);
    }
}
