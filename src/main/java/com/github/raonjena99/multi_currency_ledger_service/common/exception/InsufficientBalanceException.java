package com.github.raonjena99.multi_currency_ledger_service.common.exception;

/**
 * 보유 잔고가 요청 수량보다 적어 거래를 수행할 수 없을 때 발생합니다.
 * 입력 형식 오류가 아니라 계좌 상태와의 충돌이므로 409 로 매핑됩니다.
 */
public class InsufficientBalanceException extends RuntimeException {
    public InsufficientBalanceException(String message) { super(message); }
}
