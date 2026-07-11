package com.github.raonjena99.multi_currency_ledger_service.reconciliation.infrastructure.batch;

import org.springframework.batch.core.step.skip.SkipLimitExceededException;
import org.springframework.batch.core.step.skip.SkipPolicy;
import org.springframework.web.client.RestClientException;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;

/**
 * Spring Batch 실행 중 PG API 호출 실패 시 해당 항목을 건너뛸지(Skip) 판단하는 정책(SkipPolicy) 클래스입니다.
 */
public class PgApiSkipPolicy implements SkipPolicy {

    /**
     * 발생한 예외를 분석하여 현재 항목의 처리를 건너뛸지 여부를 결정합니다.
     * 
     * @param t 발생한 예외 (Throwable)
     * @param skipCount 현재까지 스킵된 횟수
     * @return 스킵 허용 여부 (true: 스킵, false: 배치 중단)
     * @throws SkipLimitExceededException 스킵 한도를 초과한 경우
     */
    @Override
    public boolean shouldSkip(Throwable t, long skipCount) throws SkipLimitExceededException {
        // 서킷 브레이커가 열려(Open) 더 이상 API 호출이 불가능한 상태인 경우, 해당 건을 스킵합니다.
        if (t instanceof CallNotPermittedException) {
            return true;
        }
        // 일시적인 네트워크 타임아웃 등 통신 장애가 발생한 경우, 해당 건을 스킵하고 다음 항목을 진행합니다.
        if (t instanceof RestClientException) {
            return true;
        }
        
        // 데이터베이스 에러나 문법 오류 등 내부 시스템의 치명적인 결함은 스킵하지 않고 즉시 배치(Batch) 작업을 FAILED 상태로 중단시킵니다.
        return false; 
    }
}