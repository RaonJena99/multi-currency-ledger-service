package com.github.raonjena99.multi_currency_ledger_service.common.exception;

import java.util.UUID;

/**
 * 개설하려는 계좌 ID 가 이미 있을 때 발생하는 예외입니다. (HTTP 409)
 */
public class AccountAlreadyExistsException extends RuntimeException {

    public AccountAlreadyExistsException(UUID accountId) {
        super("Account already exists: " + accountId);
    }
}
