package com.github.raonjena99.multi_currency_ledger_service.common.model;

/**
 * 거래 또는 정산 처리 중 발생할 수 있는 실패 사유를 정의한 FailureReason(실패 사유) 열거형(Enum)입니다.
 */
public enum FailureReason {
    /** 금액 불일치 오류 */
    AMOUNT_MISMATCH, 
    /** 관련된 텍스트나 데이터를 찾을 수 없는 오류 */
    TEXT_NOT_FOUND, 
    /** 허용된 시간 창(Time Window)을 초과한 오류 */
    TIME_WINDOW_EXCEEDED, 
    /** 시스템 내부 오류 */
    SYSTEM_ERROR
}
