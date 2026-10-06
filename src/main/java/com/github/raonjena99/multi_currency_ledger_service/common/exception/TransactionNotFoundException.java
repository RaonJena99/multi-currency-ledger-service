package com.github.raonjena99.multi_currency_ledger_service.common.exception;

import java.util.UUID;

/**
 * 요청한 계좌에서 해당 거래의 분개를 찾을 수 없을 때 발생하는 예외입니다. (HTTP 404)
 *
 * <p>다른 계좌의 거래이거나, 분개가 아직 비동기로 기록되지 않은 거래입니다. 두 경우를 구분해 알려주면
 * 다른 계좌의 거래 ID 가 존재하는지를 추측할 수 있으므로 같은 응답을 줍니다.
 */
public class TransactionNotFoundException extends RuntimeException {

    public TransactionNotFoundException(UUID transactionId) {
        super("Transaction not found: " + transactionId);
    }
}
