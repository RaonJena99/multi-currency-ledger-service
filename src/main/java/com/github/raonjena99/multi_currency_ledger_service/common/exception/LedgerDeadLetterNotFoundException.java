package com.github.raonjena99.multi_currency_ledger_service.common.exception;

/**
 * 요청한 ID 의 원장 데드레터가 없을 때 발생하는 예외입니다. (HTTP 404)
 */
public class LedgerDeadLetterNotFoundException extends RuntimeException {

    public LedgerDeadLetterNotFoundException(Long deadLetterId) {
        super("Ledger dead letter not found: " + deadLetterId);
    }
}
