package com.github.raonjena99.multi_currency_ledger_service.common.exception;

import java.util.UUID;

/**
 * 잔고가 남은 계좌를 해지하려 할 때 발생하는 예외입니다. (HTTP 409)
 *
 * <p>해지된 계좌는 거래·출금을 모두 막으므로, 잔고가 남은 채 해지하면 고객 돈이 묶입니다.
 * 음수 잔고(수수료 보정으로 생긴 고객 채권)도 정산 전이므로 해지하지 않습니다.
 */
public class AccountNotEmptyException extends RuntimeException {

    public AccountNotEmptyException(UUID accountId) {
        super("Account still has balance: " + accountId);
    }
}
