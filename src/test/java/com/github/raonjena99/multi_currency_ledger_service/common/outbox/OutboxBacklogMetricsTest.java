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
 * 아웃박스 백로그 지표를 실제 DB 로 검증합니다.
 *
 * <p>릴레이가 거래 속도를 따라가지 못하면 잔고는 바뀌었는데 분개가 늦게 기록됩니다. 밀린 건수와 가장 오래된
 * 미발행 이벤트의 나이를 지표로 내야 경보로 알 수 있습니다.
 */
@DisplayName("아웃박스 백로그 지표")
class OutboxBacklogMetricsTest extends IntegrationTestSupport {

    @Autowired private OutboxBacklogMetrics metrics;
    @Autowired private OutboxRepository outboxRepository;
    @Autowired private MeterRegistry meterRegistry;
    @Autowired private JdbcTemplate jdbcTemplate;

    @AfterEach
    void tearDown() {
        jdbcTemplate.execute("TRUNCATE TABLE outbox_events");
    }

    /** 만든 지 {@code ageMinutes} 분 된 이벤트. 릴레이가 집어 가지 않도록 다음 시도를 미래로 미룬다. */
    private void outbox(int ageMinutes, boolean processed, boolean deadLetter) {
        Long id = outboxRepository.save(new OutboxEvent("Ledger", "acc", "LedgerRecordingCommand", "{}", null)).getId();
        jdbcTemplate.update("UPDATE outbox_events SET created_at = now() - make_interval(mins => ?), processed = ?, "
                + "dead_letter = ?, next_attempt_at = now() + interval '1 day' WHERE id = ?",
                ageMinutes, processed, deadLetter, id);
    }

    private double gauge(String name) {
        return meterRegistry.get(name).gauge().value();
    }

    @Test
    @DisplayName("발행 대기 중인 이벤트만 세고, 가장 오래된 대기 이벤트의 나이를 초로 낸다")
    void countsPendingEventsAndOldestAge() {
        outbox(10, false, false);
        outbox(1, false, false);
        outbox(60, true, false);   // 발행 완료
        outbox(120, false, true);  // 데드레터는 별도 지표가 맡는다

        metrics.refresh();

        assertThat(gauge("outbox.backlog")).isEqualTo(2.0);
        assertThat(gauge("outbox.oldest_pending_age")).isBetween(600.0, 660.0);
    }

    @Test
    @DisplayName("대기 이벤트가 없으면 둘 다 0 이다")
    void zeroWhenNothingPending() {
        outbox(30, true, false);

        metrics.refresh();

        assertThat(gauge("outbox.backlog")).isZero();
        assertThat(gauge("outbox.oldest_pending_age")).isZero();
    }
}
