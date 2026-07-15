package com.github.raonjena99.multi_currency_ledger_service.account.domain;

/**
 * Account(계좌)의 현재 상태를 나타내는 Enum 값입니다.
 */
public enum AccountStatus {
    /** 정상 거래 가능 상태 */
    ACTIVE,      
    /** 이상 탐지(FDS) 등으로 인한 일시 정지 상태 */
    SUSPENDED,   
    /** 해지된(영구 폐쇄) 계좌 상태 */
    CLOSED       
}
