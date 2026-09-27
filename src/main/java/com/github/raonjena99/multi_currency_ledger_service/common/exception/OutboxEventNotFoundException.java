package com.github.raonjena99.multi_currency_ledger_service.common.exception;

/**
 * 요청한 ID 의 아웃박스 이벤트가 없을 때 발생하는 예외입니다. (HTTP 404)
 *
 * <p>{@link java.util.NoSuchElementException} 을 그대로 쓰면 안 됩니다. 이 예외는 {@code Optional.get()} 이나
 * 반복자 같은 프로그래밍 오류에서도 발생하므로, 전역 처리기에서 404 로 매핑하면 실제 버그가 500 대신
 * "없는 리소스"로 가려집니다.
 */
public class OutboxEventNotFoundException extends RuntimeException {

    public OutboxEventNotFoundException(Long eventId) {
        super("Outbox event not found: " + eventId);
    }
}
