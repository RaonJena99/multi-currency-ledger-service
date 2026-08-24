package com.github.raonjena99.multi_currency_ledger_service.common.exception;

/**
 * 외부 시세 공급자가 응답하지 않고 캐시도 비어 있어 시세를 전혀 확보할 수 없을 때 발생합니다.
 *
 * <p>서버 결함이 아니라 <b>외부 의존성 장애</b>이므로 503 으로 매핑됩니다. 이전 구현은 이 상황에
 * {@code IllegalStateException} 을 던져 422 로 나갔는데, 그러면 클라이언트가 자기 요청이 잘못된
 * 것으로 오인해 재시도를 포기합니다. 잠시 후 재시도하면 해결되는 상황임을 상태 코드로 알려야 합니다.
 */
public class MarketDataUnavailableException extends RuntimeException {
    public MarketDataUnavailableException(String message) { super(message); }
    public MarketDataUnavailableException(String message, Throwable cause) { super(message, cause); }
}
