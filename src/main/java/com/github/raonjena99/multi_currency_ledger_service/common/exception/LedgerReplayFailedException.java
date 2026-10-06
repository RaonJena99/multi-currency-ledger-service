package com.github.raonjena99.multi_currency_ledger_service.common.exception;

/**
 * 원장 데드레터 재처리가 실패했을 때 발생하는 예외입니다. (HTTP 422)
 *
 * <p>페이로드가 손상되었거나 원장 기록 자체가 다시 실패한 경우입니다. 데드레터는 미해결 상태로 남으며,
 * 운영자가 원인을 보고 직접 보상한 뒤 해결 처리할 수 있도록 원인을 함께 전달합니다.
 */
public class LedgerReplayFailedException extends RuntimeException {

    public LedgerReplayFailedException(Long deadLetterId, Throwable cause) {
        super("Ledger dead letter " + deadLetterId + " replay failed: " + cause, cause);
    }
}
