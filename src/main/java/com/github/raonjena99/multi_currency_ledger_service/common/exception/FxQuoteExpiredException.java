package com.github.raonjena99.multi_currency_ledger_service.common.exception;

/**
 * 환전 체결에 쓰려는 견적이 없거나 만료되었을 때 발생합니다. 422 로 매핑됩니다.
 *
 * <p>견적 저장소는 만료된 견적과 처음부터 없던 견적(또는 다른 계좌의 견적)을 구분하지 못하고, 구분해서 알려 줄 이유도
 * 없습니다. 고객이 할 일은 같습니다. 새 견적을 받아 다시 체결합니다.
 */
public class FxQuoteExpiredException extends RuntimeException {
    public FxQuoteExpiredException(String message) { super(message); }
}
