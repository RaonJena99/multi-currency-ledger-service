package com.github.raonjena99.multi_currency_ledger_service.reconciliation.application.exception;

import lombok.Getter;

/**
 * 대사(Reconciliation) 과정에서 허용 오차를 벗어나 매칭 대상을 찾지 못했을 때 발생하는 예외(Exception) 클래스입니다.
 */
@Getter
public class UnmatchableSettlementException extends RuntimeException {

    private final String externalSettlementId;

    /**
     * 매칭에 실패한 외부 정산 ID와 사유를 포함하여 예외를 생성합니다.
     * 
     * @param message 실패 사유 메시지
     * @param externalSettlementId 실패한 외부 정산 데이터의 ID
     */
    public UnmatchableSettlementException(String message, String externalSettlementId) {
        super(message);
        this.externalSettlementId = externalSettlementId;
    }

    /**
     * 대사 실패 시 성능 저하를 방지하기 위해 스택 트레이스 생성을 무효화합니다.
     * 
     * @return 현재 예외 객체 자신
     */
    @Override
    public synchronized Throwable fillInStackTrace() {
        return this; 
    }
}
