package com.github.raonjena99.multi_currency_ledger_service.common.exception;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class GlobalExceptionHandlerTest {

    private GlobalExceptionHandler exceptionHandler;

    @BeforeEach
    void setUp() {
        exceptionHandler = new GlobalExceptionHandler();
    }

    @Test
    void handleIllegalArgument() {
        IllegalArgumentException ex = new IllegalArgumentException("Invalid argument");
        ResponseEntity<ErrorResponse> response = exceptionHandler.handleIllegalArgument(ex);
        
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("INVALID_INPUT");
        assertThat(response.getBody().message()).isEqualTo("Invalid argument");
    }

    @Test
    void handleOptimisticLockingFailure() {
        OptimisticLockingFailureException ex = new OptimisticLockingFailureException("Lock failed");
        ResponseEntity<ErrorResponse> response = exceptionHandler.handleOptimisticLockingFailure(ex);
        
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("CONCURRENCY_CONFLICT");
        assertThat(response.getBody().message()).isEqualTo("The asset state has been modified by another transaction.");
    }

    @Test
    void handleIllegalState() {
        IllegalStateException ex = new IllegalStateException("Invalid state");
        ResponseEntity<ErrorResponse> response = exceptionHandler.handleIllegalState(ex);
        
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("DOMAIN_RULE_VIOLATION");
        assertThat(response.getBody().message()).isEqualTo("Invalid state");
    }
}
