package com.github.raonjena99.multi_currency_ledger_service.common.exception;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ExceptionCoverageTest {

    @Test
    void eventPublishingException_should_store_message_and_cause() {
        Throwable cause = new RuntimeException("cause");
        EventPublishingException ex = new EventPublishingException("msg", cause);
        assertThat(ex.getMessage()).isEqualTo("msg");
        assertThat(ex.getCause()).isEqualTo(cause);
    }

    @Test
    void duplicateTradeRequestException_should_store_message() {
        DuplicateTradeRequestException ex = new DuplicateTradeRequestException("msg");
        assertThat(ex.getMessage()).isEqualTo("msg");
    }

    @Test
    void invalidAccountStateException_should_store_message() {
        InvalidAccountStateException ex = new InvalidAccountStateException("msg");
        assertThat(ex.getMessage()).isEqualTo("msg");
    }
}
