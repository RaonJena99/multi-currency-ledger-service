package com.github.raonjena99.multi_currency_ledger_service.reconciliation.infrastructure.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.step.skip.SkipLimitExceededException;
import org.springframework.web.client.RestClientException;

import com.github.raonjena99.multi_currency_ledger_service.reconciliation.application.exception.UnmatchableSettlementException;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;

class ReconciliationCompositeSkipPolicyTest {

    @Test
    @DisplayName("shouldSkip - 복합 예외 조건 검증")
    void shouldSkip() throws Exception {
        ReconciliationCompositeSkipPolicy policy = new ReconciliationCompositeSkipPolicy(2);
        CallNotPermittedException ex = mock(CallNotPermittedException.class);
        
        assertThat(policy.shouldSkip(ex, 0)).isTrue();
        assertThat(policy.shouldSkip(new RestClientException("network error"), 0)).isTrue();
        
        assertThat(policy.shouldSkip(new UnmatchableSettlementException("err", "id"), 0)).isTrue();
        assertThat(policy.shouldSkip(new UnmatchableSettlementException("err", "id"), 1)).isTrue();
        
        assertThatThrownBy(() -> policy.shouldSkip(new UnmatchableSettlementException("err", "id"), 2))
            .isInstanceOf(SkipLimitExceededException.class);
            
        assertThat(policy.shouldSkip(new RuntimeException("other error"), 0)).isFalse();
    }
}
