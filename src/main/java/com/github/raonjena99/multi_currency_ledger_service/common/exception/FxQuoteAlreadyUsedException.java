package com.github.raonjena99.multi_currency_ledger_service.common.exception;

/**
 * 이미 체결된 견적으로 다시 체결하려 할 때 발생합니다. 409 로 매핑됩니다.
 *
 * <p>견적 하나는 한 번만 쓸 수 있습니다. 같은 멱등성 키로 다시 보낸 요청은 이 예외가 아니라 처음 체결의 결과를 돌려받고,
 * 멱등성 키를 바꿔 같은 견적을 다시 쓰려는 요청이 이 예외를 받습니다.
 */
public class FxQuoteAlreadyUsedException extends RuntimeException {
    public FxQuoteAlreadyUsedException(String message) { super(message); }
}
