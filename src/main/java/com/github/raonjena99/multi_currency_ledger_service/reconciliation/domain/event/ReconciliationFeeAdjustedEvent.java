package com.github.raonjena99.multi_currency_ledger_service.reconciliation.domain.event;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.github.raonjena99.multi_currency_ledger_service.common.domain.Money;

/**
 * 정산 대사 과정에서 수수료 오차가 발생했을 때 발행되는 도메인 이벤트
 */
public record ReconciliationFeeAdjustedEvent(
    UUID settlementId,
    UUID internalTransactionId,
    UUID accountId,             
    Money feeDifference,
    OffsetDateTime occurredAt
) {
    public ReconciliationFeeAdjustedEvent {
        if (settlementId == null || internalTransactionId == null || accountId == null) {
            throw new IllegalArgumentException("Identifiers cannot be null");
        }
        if (feeDifference == null) {
            throw new IllegalArgumentException("Fee difference cannot be null");
        }
        if (occurredAt == null) {
            occurredAt = OffsetDateTime.now();
        }
    }
    
    /**
     * 비즈니스 레이어에서 이벤트를 쉽게 발행하기 위한 정적 팩토리 메서드
     */
    public static ReconciliationFeeAdjustedEvent of(
            UUID settlementId, 
            UUID internalTransactionId, 
            UUID accountId, 
            Money feeDifference) {
        
        return new ReconciliationFeeAdjustedEvent(
                settlementId, 
                internalTransactionId, 
                accountId, 
                feeDifference, 
                OffsetDateTime.now()
        );
    }
}
