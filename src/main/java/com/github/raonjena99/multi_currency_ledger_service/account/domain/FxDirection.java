package com.github.raonjena99.multi_currency_ledger_service.account.domain;

/**
 * 환전의 방향입니다. 환전은 항상 계좌의 기준 통화와 외화 사이에서 일어나므로, 방향은 고객이 외화를 사는지
 * 파는지로 정해집니다.
 */
public enum FxDirection {

    /** 고객이 기준 통화를 내고 외화를 받는다. 시세보다 비싼 환율이 적용된다. */
    BUY_FOREIGN,

    /** 고객이 외화를 내고 기준 통화를 받는다. 시세보다 싼 환율이 적용된다. */
    SELL_FOREIGN
}
