package com.github.raonjena99.multi_currency_ledger_service.reconciliation.infrastructure.batch;

import org.springframework.batch.core.step.skip.SkipLimitExceededException;
import org.springframework.batch.core.step.skip.SkipPolicy;
import org.springframework.web.client.RestClientException;

import com.github.raonjena99.multi_currency_ledger_service.reconciliation.application.exception.UnmatchableSettlementException;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;

/**
 * 대사 배치 처리 중 발생하는 다양한 예외들에 대해 복합적인 스킵 정책(SkipPolicy)을 정의한 클래스입니다.
 * 네트워크 예외와 비즈니스 예외(매칭 실패)를 각각 다른 기준으로 스킵 처리합니다.
 */
public class ReconciliationCompositeSkipPolicy implements SkipPolicy {
    
    private final int businessSkipLimit;

    public ReconciliationCompositeSkipPolicy(int businessSkipLimit) {
        this.businessSkipLimit = businessSkipLimit;
    }

    /**
     * 발생한 예외를 확인하고 계속 진행할지(Skip) 중단할지 판단합니다.
     * 
     * @param t 발생한 예외 (Throwable)
     * @param skipCount 누적 스킵 횟수
     * @return 스킵 여부 (true: 스킵, false: 배치 실패)
     * @throws SkipLimitExceededException 비즈니스 스킵 한도(businessSkipLimit)를 초과한 경우
     */
    @Override
    public boolean shouldSkip(Throwable t, long skipCount) throws SkipLimitExceededException {
        // 외부 PG 통신 장애 및 서킷 브레이커 차단(CallNotPermittedException, RestClientException)은 횟수 제한 없이 항상 스킵합니다.
        if (t instanceof CallNotPermittedException || t instanceof RestClientException) {
            return true; 
        }
        
        // 비즈니스 매칭 실패(UnmatchableSettlementException)의 경우 설정된 허용 한도(businessSkipLimit) 내에서만 스킵을 허용합니다.
        if (t instanceof UnmatchableSettlementException) {
            if (skipCount < businessSkipLimit) {
                return true;
            }
            // 한도를 초과하면 배치를 중단하기 위해 예외를 던집니다.
            throw new SkipLimitExceededException(businessSkipLimit, t);
        }
        
        // 그 외의 치명적인 시스템 예외는 스킵하지 않고 배치를 실패로 처리합니다.
        return false;
    }
}
