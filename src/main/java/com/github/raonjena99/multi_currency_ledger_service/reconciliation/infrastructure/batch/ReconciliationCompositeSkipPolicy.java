package com.github.raonjena99.multi_currency_ledger_service.reconciliation.infrastructure.batch;

import org.springframework.batch.core.step.skip.SkipLimitExceededException;
import org.springframework.batch.core.step.skip.SkipPolicy;
import org.springframework.web.client.RestClientException;

import com.github.raonjena99.multi_currency_ledger_service.reconciliation.application.exception.UnmatchableSettlementException;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;

public class ReconciliationCompositeSkipPolicy implements SkipPolicy {
    
    private final int businessSkipLimit;

    public ReconciliationCompositeSkipPolicy(int businessSkipLimit) {
        this.businessSkipLimit = businessSkipLimit;
    }

    @Override
    public boolean shouldSkip(Throwable t, long skipCount) throws SkipLimitExceededException {
        // 외부 PG 통신 장애 및 서킷 브레이커 차단
        if (t instanceof CallNotPermittedException || t instanceof RestClientException) {
            return true; 
        }
        
        // 비즈니스 매칭 실패
        if (t instanceof UnmatchableSettlementException) {
            if (skipCount < businessSkipLimit) {
                return true;
            }
            throw new SkipLimitExceededException(businessSkipLimit, t);
        }
        
        // 그 외의 예외
        return false;
    }
}
