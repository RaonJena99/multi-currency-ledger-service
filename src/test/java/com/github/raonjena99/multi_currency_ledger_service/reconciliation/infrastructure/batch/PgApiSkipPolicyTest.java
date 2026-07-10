package com.github.raonjena99.multi_currency_ledger_service.reconciliation.infrastructure.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClientException;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;

class PgApiSkipPolicyTest {

    @Test
    @DisplayName("shouldSkip - 지정된 예외에 대해 올바른 값을 반환한다")
    void shouldSkip() throws Exception {
        PgApiSkipPolicy policy = new PgApiSkipPolicy();
        CallNotPermittedException ex = mock(CallNotPermittedException.class);
        
        assertThat(policy.shouldSkip(ex, 0)).isTrue();
        assertThat(policy.shouldSkip(new RestClientException("network error"), 0)).isTrue();
        assertThat(policy.shouldSkip(new RuntimeException("other error"), 0)).isFalse();
    }
}
