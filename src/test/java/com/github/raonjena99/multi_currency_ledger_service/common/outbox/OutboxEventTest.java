package com.github.raonjena99.multi_currency_ledger_service.common.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class OutboxEventTest {

    @Test
    void should_create_outbox_event_with_correlation_id() {
        OutboxEvent event = new OutboxEvent("aggType", "agg123", "eventTopic", "{\"payload\":\"test\"}", "corr-123");

        assertThat(event.getAggregateType()).isEqualTo("aggType");
        assertThat(event.getAggregateId()).isEqualTo("agg123");
        assertThat(event.getEventType()).isEqualTo("eventTopic");
        assertThat(event.getPayload()).isEqualTo("{\"payload\":\"test\"}");
        assertThat(event.getCorrelationId()).isEqualTo("corr-123");
        assertThat(event.isProcessed()).isFalse();
        assertThat(event.isDeadLetter()).isFalse();
        assertThat(event.getRetryCount()).isZero();
    }

    @Test
    void recordFailure_should_increment_retry_count() {
        OutboxEvent event = new OutboxEvent("t", "1", "e", "p", "c");

        event.recordFailure("First failure");
        assertThat(event.getRetryCount()).isEqualTo(1);
        assertThat(event.getErrorMessage()).isEqualTo("First failure");
        assertThat(event.isDeadLetter()).isFalse();
        assertThat(event.isProcessed()).isFalse();
        // 실패 시 다음 시도는 백오프만큼 미뤄져야 한다. 백오프 없이 폴링 주기마다 즉시
        // 재시도하면 짧은 브로커 장애에도 재시도 예산이 소진된다.
        assertThat(event.getNextAttemptAt()).isNotNull();
        assertThat(event.getNextAttemptAt()).isAfter(java.time.OffsetDateTime.now());
    }

    @Test
    void recordFailure_should_apply_exponential_backoff() {
        OutboxEvent event = new OutboxEvent("t", "1", "e", "p", "c");

        event.recordFailure("F1");
        java.time.OffsetDateTime first = event.getNextAttemptAt();
        event.recordFailure("F2");
        java.time.OffsetDateTime second = event.getNextAttemptAt();

        assertThat(second).isAfter(first);
    }

    @Test
    void recordFailure_should_truncate_long_error_messages() {
        OutboxEvent event = new OutboxEvent("t", "1", "e", "p", "c");
        
        String longError = "A".repeat(600);
        event.recordFailure(longError);
        
        assertThat(event.getErrorMessage()).hasSize(500);
        assertThat(event.getErrorMessage()).isEqualTo("A".repeat(500));
    }
    
    @Test
    void recordFailure_should_handle_null_error_messages() {
        OutboxEvent event = new OutboxEvent("t", "1", "e", "p", "c");
        
        event.recordFailure(null);
        
        assertThat(event.getErrorMessage()).isNull();
        assertThat(event.getRetryCount()).isEqualTo(1);
    }

    @Test
    void recordFailure_should_mark_as_dead_letter_only_after_max_retries() {
        OutboxEvent event = new OutboxEvent("t", "1", "e", "p", "c");

        // 3회 실패로는 데드레터가 되지 않는다. 임계값이 너무 낮으면(백오프와 무관하게)
        // 몇 분짜리 브로커 장애에도 이벤트가 영구 격리되어 at-least-once 가 깨진다.
        for (int i = 0; i < OutboxEvent.MAX_RETRY_COUNT - 1; i++) {
            event.recordFailure("F" + i);
        }
        assertThat(event.isDeadLetter()).isFalse();
        assertThat(event.isProcessed()).isFalse();

        event.recordFailure("final");

        assertThat(event.getRetryCount()).isEqualTo(OutboxEvent.MAX_RETRY_COUNT);
        assertThat(event.isDeadLetter()).isTrue();
        assertThat(event.isProcessed()).isTrue(); // Marked as processed to stop retries
    }

    @Test
    void requeue_should_reset_dead_letter_state() {
        OutboxEvent event = new OutboxEvent("t", "1", "e", "p", "c");
        for (int i = 0; i < OutboxEvent.MAX_RETRY_COUNT; i++) {
            event.recordFailure("F" + i);
        }
        assertThat(event.isDeadLetter()).isTrue();

        event.requeue();

        assertThat(event.isDeadLetter()).isFalse();
        assertThat(event.isProcessed()).isFalse();
        assertThat(event.getRetryCount()).isZero();
        assertThat(event.getNextAttemptAt()).isNull();
        assertThat(event.getLockedAt()).isNull();
    }

    @Test
    void requeue_should_reject_non_dead_letter_events() {
        OutboxEvent event = new OutboxEvent("t", "1", "e", "p", "c");

        org.assertj.core.api.Assertions.assertThatThrownBy(event::requeue)
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void lock_and_unlock_should_manage_lockedAt_time() {
        OutboxEvent event = new OutboxEvent("t", "1", "e", "p", "c");
        
        assertThat(event.getLockedAt()).isNull();
        
        event.lock();
        assertThat(event.getLockedAt()).isNotNull();
        
        event.unlock();
        assertThat(event.getLockedAt()).isNull();
    }
    
    @Test
    void markAsProcessed_should_set_processed_flag_to_true() {
        OutboxEvent event = new OutboxEvent("t", "1", "e", "p", "c");
        event.markAsProcessed();
        assertThat(event.isProcessed()).isTrue();
    }
}
