package com.github.raonjena99.multi_currency_ledger_service.common.exception;

import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import lombok.extern.slf4j.Slf4j;

/**
 * 애플리케이션 전역에서 발생하는 예외를 처리하는 GlobalExceptionHandler(전역 예외 처리기) 클래스입니다.
 * 각 예외 유형에 맞춰 적절한 HTTP 상태 코드와 ErrorResponse(에러 응답)를 반환합니다.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 잘못된 요청 파라미터 등 클라이언트 입력 오류(IllegalArgumentException)를 처리합니다.
     *
     * @param e 발생한 IllegalArgumentException 예외
     * @return HTTP 400 (Bad Request) 상태 코드와 에러 응답 객체
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException e) {
        log.warn("Invalid input: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("INVALID_INPUT", e.getMessage()));
    }

    /**
     * 동시성 제어 실패 시 발생하는 예외(OptimisticLockingFailureException)를 처리합니다.
     * 데이터의 상태 충돌이 발생한 경우 클라이언트에게 재시도를 유도합니다.
     *
     * @param e 발생한 OptimisticLockingFailureException 예외
     * @return HTTP 409 (Conflict) 상태 코드와 에러 응답 객체
     */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ErrorResponse> handleOptimisticLockingFailure(OptimisticLockingFailureException e) {
        log.warn("Concurrency conflict detected. Transaction requires retry.", e);
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("CONCURRENCY_CONFLICT", "The asset state has been modified by another transaction."));
    }
    
    /**
     * 도메인 규칙 위반이나 복식부기 검증 실패 시 발생하는 예외(IllegalStateException)를 처리합니다.
     *
     * @param e 발생한 IllegalStateException 예외
     * @return HTTP 422 (Unprocessable Content) 상태 코드와 에러 응답 객체
     */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ErrorResponse> handleIllegalState(IllegalStateException e) {
        log.error("Domain rule violation: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT)
                .body(new ErrorResponse("DOMAIN_RULE_VIOLATION", e.getMessage()));
    }

    /**
     * 계좌 상태가 유효하지 않을 때 (HTTP 422)
     */
    @ExceptionHandler(InvalidAccountStateException.class)
    public ResponseEntity<ErrorResponse> handleInvalidAccountState(InvalidAccountStateException e) {
        log.warn("Invalid account state: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT)
                .body(new ErrorResponse("INVALID_ACCOUNT_STATE", e.getMessage()));
    }

    /**
     * 멱등성 충돌 - 중복된 결제 요청 시 (HTTP 409)
     */
    @ExceptionHandler(DuplicateTradeRequestException.class)
    public ResponseEntity<ErrorResponse> handleDuplicateTradeRequest(DuplicateTradeRequestException e) {
        log.warn("Duplicate trade request detected: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("DUPLICATE_REQUEST", e.getMessage()));
    }
    /**
     * 정산 매칭 상태 전이 오류 (HTTP 422)
     */
    @ExceptionHandler(InvalidSettlementStateException.class)
    public ResponseEntity<ErrorResponse> handleInvalidSettlementState(InvalidSettlementStateException e) {
        log.warn("Invalid settlement state transition: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT)
                .body(new ErrorResponse("INVALID_SETTLEMENT_STATE", e.getMessage()));
    }
    /**
     * 시스템 크리티컬 오류: 대차 불일치 (HTTP 500)
     * 이 에러는 매우 심각한 상황이므로 Error 레벨로 로깅하여 모니터링 알람(Slack 등)을 유발해야 합니다.
     */
    @ExceptionHandler(DoubleEntryImbalanceException.class)
    public ResponseEntity<ErrorResponse> handleDoubleEntryImbalance(DoubleEntryImbalanceException e) {
        log.error("CRITICAL: Double-entry imbalance detected in ledger!", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse("SYSTEM_CRITICAL_ERROR", "A fatal system error occurred while processing the ledger."));
    }
}