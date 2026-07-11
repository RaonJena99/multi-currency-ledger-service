package com.github.raonjena99.multi_currency_ledger_service.common.exception;

/**
 * API 에러 발생 시 클라이언트에게 반환되는 공통 ErrorResponse(에러 응답) DTO입니다.
 *
 * @param code    에러 코드 (예: INVALID_INPUT, CONCURRENCY_CONFLICT)
 * @param message 상세 에러 메시지
 */
public record ErrorResponse(String code, String message) {
}