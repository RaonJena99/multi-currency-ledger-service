package com.github.raonjena99.multi_currency_ledger_service.reconciliation.domain.event;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.github.raonjena99.multi_currency_ledger_service.common.domain.Money;

/**
 * 정산 대사 과정에서 수수료 오차가 발생하여 보정이 필요할 때 발행되는 도메인 이벤트(Domain Event) 레코드(Record)입니다.
 */
public record ReconciliationFeeAdjustedEvent(
    UUID settlementId,
    UUID internalTransactionId,
    UUID accountId,             
    Money feeDifference,
    OffsetDateTime occurredAt
) {
    /**
     * 필드 유효성을 검사하고 이벤트 발생 일시를 초기화하는 생성자입니다.
     */
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
     * 비즈니스 레이어에서 이벤트를 쉽게 발행하기 위한 정적 팩토리(Static Factory) 메서드입니다.
     * 
     * @param settlementId 외부 정산 데이터의 ID (UUID)
     * @param internalTransactionId 매칭된 내부 거래의 ID (UUID)
     * @param accountId 대상 계좌 ID (UUID)
     * @param feeDifference 보정할 수수료 차액 (Money)
     * @return 생성된 수수료 보정 이벤트 객체
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
