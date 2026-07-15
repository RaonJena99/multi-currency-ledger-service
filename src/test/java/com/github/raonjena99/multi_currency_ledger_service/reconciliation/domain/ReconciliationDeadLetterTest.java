package com.github.raonjena99.multi_currency_ledger_service.reconciliation.domain;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.github.raonjena99.multi_currency_ledger_service.common.model.FailureReason;

class ReconciliationDeadLetterTest {

    @Test
    @DisplayName("이미 해결된 DeadLetter를 다시 해결하려고 하면 예외 발생")
    void resolve_already_resolved_exception() {
        ReconciliationDeadLetter deadLetter = ReconciliationDeadLetter.isolate(
            UUID.randomUUID(), FailureReason.TEXT_NOT_FOUND, "error", "payload"
        );
        
        // 첫 번째 해결
        deadLetter.markAsResolved();
        
        // 두 번째 해결 (예외 발생)
        assertThatThrownBy(deadLetter::markAsResolved)
            .isInstanceOf(IllegalStateException.class);
    }
}
