package com.github.raonjena99.multi_currency_ledger_service.account.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class IdempotencyRecordTest {

    @Test
    void should_create_record_and_return_id() {
        IdempotencyRecord record = new IdempotencyRecord("test-key-123");
        
        assertThat(record.getId()).isEqualTo("test-key-123");
        assertThat(record.getIdempotencyKey()).isEqualTo("test-key-123");
        assertThat(record.getCreatedAt()).isNotNull();
    }

    @Test
    void isNew_should_always_return_true() {
        IdempotencyRecord record = new IdempotencyRecord("test-key-123");
        assertThat(record.isNew()).isTrue();
    }
}
