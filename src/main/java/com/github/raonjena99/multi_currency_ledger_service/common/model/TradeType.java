package com.github.raonjena99.multi_currency_ledger_service.common.model;

/**
 * 거래의 종류를 나타내는 TradeType(거래 유형) 열거형(Enum)입니다.
 */
public enum TradeType {
    /** 매수 (자산을 구매함) */
    BUY, 
    /** 매도 (자산을 판매함) */
    SELL,
    /** 입금 (외부에서 법정화폐가 들어옴) */
    DEPOSIT,
    /** 출금 (법정화폐가 외부로 나감) */
    WITHDRAWAL
}