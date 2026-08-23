package com.github.raonjena99.multi_currency_ledger_service.common.exception;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void handleIllegalArgument() {
        ResponseEntity<ErrorResponse> response = handler.handleIllegalArgument(new IllegalArgumentException("invalid"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().code()).isEqualTo("INVALID_INPUT");
        assertThat(response.getBody().message()).isEqualTo("invalid");
    }

    @Test
    void handleOptimisticLockingFailure() {
        ResponseEntity<ErrorResponse> response = handler.handleOptimisticLockingFailure(new OptimisticLockingFailureException("conflict"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().code()).isEqualTo("CONCURRENCY_CONFLICT");
    }

    @Test
    void handleIllegalState() {
        ResponseEntity<ErrorResponse> response = handler.handleIllegalState(new IllegalStateException("violation"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(response.getBody().code()).isEqualTo("DOMAIN_RULE_VIOLATION");
    }

    @Test
    void handleInvalidAccountState() {
        ResponseEntity<ErrorResponse> response = handler.handleInvalidAccountState(new InvalidAccountStateException("invalid_state"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(response.getBody().code()).isEqualTo("INVALID_ACCOUNT_STATE");
    }

    @Test
    void handleDuplicateTradeRequest() {
        ResponseEntity<ErrorResponse> response = handler.handleDuplicateTradeRequest(new DuplicateTradeRequestException("duplicate"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().code()).isEqualTo("DUPLICATE_REQUEST");
    }

    @Test
    void handleInvalidSettlementState() {
        ResponseEntity<ErrorResponse> response = handler.handleInvalidSettlementState(new InvalidSettlementStateException("invalid_settlement"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(response.getBody().code()).isEqualTo("INVALID_SETTLEMENT_STATE");
    }

    @Test
    void handleDoubleEntryImbalance() {
        ResponseEntity<ErrorResponse> response = handler.handleDoubleEntryImbalance(new DoubleEntryImbalanceException("imbalance"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody().code()).isEqualTo("SYSTEM_CRITICAL_ERROR");
    }

    @Test
    void handleUnhandledException() {
        ResponseEntity<ErrorResponse> response = handler.handleUnhandledException(new RuntimeException("unknown error"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody().code()).isEqualTo("INTERNAL_SERVER_ERROR");
    }
}
