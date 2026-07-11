package com.github.raonjena99.multi_currency_ledger_service.account.domain;

public enum AccountStatus {
    ACTIVE,      // 정상 거래 가능
    SUSPENDED,   // 이상 탐지 등으로 인한 일시 정지
    CLOSED       // 해지된 계좌
}
