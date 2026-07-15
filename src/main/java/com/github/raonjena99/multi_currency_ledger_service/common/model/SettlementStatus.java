package com.github.raonjena99.multi_currency_ledger_service.common.model;

/**
 * 정산 처리의 상태를 나타내는 SettlementStatus(정산 상태) 열거형(Enum)입니다.
 */
public enum SettlementStatus {
    /** 정산 대기 중 */
    PENDING, 
    /** 정산 조건이 일치하여 완료됨 */
    MATCHED, 
    /** 정산 조건이 불일치하여 실패함 */
    UNMATCHED, 
    /** 관리자 등에 의해 수동으로 해결됨 */
    MANUALLY_RESOLVED
}
