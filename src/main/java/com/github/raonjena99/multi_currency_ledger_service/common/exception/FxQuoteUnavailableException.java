package com.github.raonjena99.multi_currency_ledger_service.common.exception;

/**
 * 견적 저장소(Redis)를 쓸 수 없어 견적을 내거나 확인할 수 없을 때 발생합니다. 503 으로 매핑됩니다.
 *
 * <p>서버 결함이 아니라 의존성 장애이므로 잠시 뒤 재시도하면 해결될 수 있습니다. 견적을 저장하지 못한 채 견적을
 * 내주면 고객이 그 견적으로 체결할 수 없으므로, 견적은 내주지 않습니다.
 */
public class FxQuoteUnavailableException extends RuntimeException {
    public FxQuoteUnavailableException(String message, Throwable cause) { super(message, cause); }
}
