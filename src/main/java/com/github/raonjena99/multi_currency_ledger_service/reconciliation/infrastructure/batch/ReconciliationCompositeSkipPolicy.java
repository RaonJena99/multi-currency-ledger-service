package com.github.raonjena99.multi_currency_ledger_service.reconciliation.infrastructure.batch;

import org.springframework.batch.core.step.skip.SkipLimitExceededException;
import org.springframework.batch.core.step.skip.SkipPolicy;
import org.springframework.web.client.RestClientException;

import com.github.raonjena99.multi_currency_ledger_service.reconciliation.application.exception.UnmatchableSettlementException;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;

/**
 * 대사 배치 처리 중 발생하는 예외들에 대한 복합 스킵 정책입니다.
 *
 * <p>인프라 장애 스킵에도 <b>반드시 한도가 있어야 합니다.</b> 이전 구현은 통신 장애를 횟수 제한 없이
 * 스킵했기 때문에, 외부 API 가 죽으면 한 달치를 전부 스킵하고도 잡이 COMPLETED 로 끝났습니다.
 * 아무 일도 하지 않은 배치가 성공으로 보고되면 장애를 감지할 방법이 없습니다.
 */
public class ReconciliationCompositeSkipPolicy implements SkipPolicy {

    private final int businessSkipLimit;
    private final int infrastructureSkipLimit;

    /**
     * @param businessSkipLimit       매칭 실패(비즈니스) 허용 건수
     * @param infrastructureSkipLimit 통신 장애 허용 건수. 초과하면 배치를 실패로 종료합니다.
     */
    public ReconciliationCompositeSkipPolicy(int businessSkipLimit, int infrastructureSkipLimit) {
        this.businessSkipLimit = businessSkipLimit;
        this.infrastructureSkipLimit = infrastructureSkipLimit;
    }

    /**
     * 발생한 예외를 확인하고 계속 진행할지(Skip) 중단할지 판단합니다.
     *
     * @param t 발생한 예외 (Throwable)
     * @param skipCount 누적 스킵 횟수
     * @return 스킵 여부 (true: 스킵, false: 배치 실패)
     * @throws SkipLimitExceededException 허용 한도를 초과한 경우
     */
    @Override
    public boolean shouldSkip(Throwable t, long skipCount) throws SkipLimitExceededException {
        // 외부 PG 통신 장애 및 서킷 브레이커 차단은 한도 내에서만 스킵한다.
        if (contains(t, CallNotPermittedException.class) || contains(t, RestClientException.class)) {
            if (skipCount < infrastructureSkipLimit) {
                return true;
            }
            throw new SkipLimitExceededException(infrastructureSkipLimit, t);
        }

        // 비즈니스 매칭 실패는 설정된 허용 한도 내에서만 스킵을 허용한다.
        if (contains(t, UnmatchableSettlementException.class)) {
            if (skipCount < businessSkipLimit) {
                return true;
            }
            throw new SkipLimitExceededException(businessSkipLimit, t);
        }

        // 그 외의 치명적인 시스템 예외는 스킵하지 않고 배치를 실패로 처리한다.
        return false;
    }

    /**
     * Spring Batch 는 프로세서 예외를 감싸서 전달할 수 있으므로 원인 체인 전체를 확인합니다.
     */
    private boolean contains(Throwable t, Class<? extends Throwable> type) {
        Throwable cause = t;
        while (cause != null) {
            if (type.isInstance(cause)) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }
}
