package com.github.raonjena99.multi_currency_ledger_service.common.exception;

/**
 * 환전할 수 없는 통화 조합일 때 발생합니다. 422 로 매핑됩니다.
 *
 * <p>환전은 계좌의 기준 통화와 설정에 등록된 외화 사이에서만 할 수 있습니다. 기준 통화가 끼지 않았거나(외화끼리)
 * 스프레드가 정해지지 않은 통화는 재시도해도 결과가 같은 <b>영구</b> 오류입니다.
 */
public class UnsupportedFxPairException extends RuntimeException {
    public UnsupportedFxPairException(String message) { super(message); }
}
