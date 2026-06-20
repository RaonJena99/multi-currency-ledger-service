package com.github.raonjena99.multi_currency_ledger_service.reconciliation.infrastructure.batch;

import org.springframework.batch.core.step.skip.SkipLimitExceededException;
import org.springframework.batch.core.step.skip.SkipPolicy;
import org.springframework.web.client.RestClientException;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;

public class PgApiSkipPolicy implements SkipPolicy {

    @Override
    public boolean shouldSkip(Throwable t, long skipCount) throws SkipLimitExceededException {
        // 서킷 브레이커가 열려 통신이 차단된 경우
        if (t instanceof CallNotPermittedException) {
            return true;
        }
        // 일시적인 네트워크 타임아웃의 경우
        if (t instanceof RestClientException) {
            return true;
        }
        
        // 데이터베이스 에러나 문법 에러 등 내부 결함은 스킵하지 않고 즉시 배치를 FAILED 처리
        return false; 
    }
}