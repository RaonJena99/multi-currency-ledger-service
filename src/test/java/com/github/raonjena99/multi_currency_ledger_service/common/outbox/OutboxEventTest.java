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
    void recordFailure_should_mark_as_dead_letter_after_3_retries() {
        OutboxEvent event = new OutboxEvent("t", "1", "e", "p", "c");

        event.recordFailure("F1");
        event.recordFailure("F2");
        event.recordFailure("F3");

        assertThat(event.getRetryCount()).isEqualTo(3);
        assertThat(event.isDeadLetter()).isTrue();
        assertThat(event.isProcessed()).isTrue(); // Marked as processed to stop retries
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
