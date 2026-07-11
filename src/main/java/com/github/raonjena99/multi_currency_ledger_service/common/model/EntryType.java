package com.github.raonjena99.multi_currency_ledger_service.common.model;

/**
 * 복식부기 원장의 기입 방향을 나타내는 EntryType(기입 유형) 열거형(Enum)입니다.
 * 차변(DEBIT)과 대변(CREDIT)을 구분합니다.
 */
public enum EntryType {
    /** 차변 (자산의 증가, 부채/자본의 감소 등을 의미) */
    DEBIT, 
    /** 대변 (자산의 감소, 부채/자본의 증가 등을 의미) */
    CREDIT
}
