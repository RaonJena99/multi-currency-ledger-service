package com.github.raonjena99.multi_currency_ledger_service.common.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class OutboxEventTest {

    @Test
    void recordFailure_variousBranches() {
        OutboxEvent event = new OutboxEvent("Type", "ID", "Event", "Payload", "test-corr-id");
        
        // Branch 1: error == null
        event.recordFailure(null);
        assertThat(event.getErrorMessage()).isNull();
        assertThat(event.isDeadLetter()).isFalse();

        // Branch 2: error > 500 chars
        String longError = "A".repeat(600);
        event.recordFailure(longError);
        assertThat(event.getErrorMessage()).hasSize(500);

        // Branch 3: retryCount >= 3
        event.recordFailure("Third error");
        assertThat(event.isDeadLetter()).isTrue();
    }
}
