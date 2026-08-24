package com.github.raonjena99.multi_currency_ledger_service.common.exception;

import java.util.UUID;

/**
 * 요청된 계좌를 찾을 수 없을 때 발생합니다. 404 로 매핑됩니다.
 */
public class AccountNotFoundException extends RuntimeException {
    public AccountNotFoundException(UUID accountId) {
        super("Account not found: " + accountId);
    }
}
