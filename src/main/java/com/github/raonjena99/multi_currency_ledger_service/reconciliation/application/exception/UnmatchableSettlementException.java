package com.github.raonjena99.multi_currency_ledger_service.reconciliation.application.exception;

import lombok.Getter;

/**
 * 대사(Reconciliation) 과정에서 허용 오차를 벗어나 매칭 대상을 찾지 못했을 때 발생하는 예외
 */
@Getter
public class UnmatchableSettlementException extends RuntimeException {

    private final String externalSettlementId;

    public UnmatchableSettlementException(String message, String externalSettlementId) {
        super(message);
        this.externalSettlementId = externalSettlementId;
    }

    /**
     * 대사 실패시 스택 트레이스 무효화하여 성능 저하 차단.
     */
    @Override
    public synchronized Throwable fillInStackTrace() {
        return this; 
    }
}
