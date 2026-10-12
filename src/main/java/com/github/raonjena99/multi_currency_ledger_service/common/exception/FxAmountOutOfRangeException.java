package com.github.raonjena99.multi_currency_ledger_service.common.exception;

/**
 * 환전 금액이 통화별 최소·최대 한도를 벗어났을 때 발생합니다. 422 로 매핑됩니다.
 */
public class FxAmountOutOfRangeException extends RuntimeException {
    public FxAmountOutOfRangeException(String message) { super(message); }
}
