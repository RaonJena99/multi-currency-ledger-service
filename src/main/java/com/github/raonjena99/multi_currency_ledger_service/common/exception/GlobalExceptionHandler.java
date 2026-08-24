package com.github.raonjena99.multi_currency_ledger_service.common.exception;

import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import lombok.extern.slf4j.Slf4j;

/**
 * 애플리케이션 전역에서 발생하는 예외를 처리하는 GlobalExceptionHandler(전역 예외 처리기) 클래스입니다.
 *
 * <p>{@link ResponseEntityExceptionHandler} 를 상속하는 것이 핵심입니다. 상속하지 않은 채
 * {@code @ExceptionHandler(Exception.class)} 캐치올만 두면, Spring MVC 의 표준 4xx 예외
 * ({@code MethodArgumentNotValidException}, {@code HttpMessageNotReadableException},
 * {@code MethodArgumentTypeMismatchException} 등)를 기본 리졸버보다 먼저 가로채
 * <b>모든 클라이언트 오류가 500 으로 나갑니다.</b> 그러면 컨트롤러의 {@code @Valid} 검증이
 * 전부 무력화되고, 클라이언트는 자기 실수와 서버 장애를 구분할 수 없게 됩니다.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    /**
     * 상위 클래스가 처리하는 표준 MVC 예외의 응답 본문을 이 서비스의 ErrorResponse 계약으로 바꿉니다.
     * 상태 코드는 Spring 이 판단한 값을 그대로 보존합니다.
     */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, @Nullable Object body,
            HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {

        if (statusCode.is5xxServerError()) {
            log.error("Unhandled MVC exception: {}", ex.getMessage(), ex);
        } else {
            log.warn("Client error ({}): {}", statusCode.value(), ex.getMessage());
        }

        ErrorResponse errorResponse = new ErrorResponse(resolveCode(statusCode), clientSafeMessage(ex, statusCode));
        return new ResponseEntity<>(errorResponse, headers, statusCode);
    }

    private String resolveCode(HttpStatusCode statusCode) {
        if (statusCode.value() == HttpStatus.BAD_REQUEST.value()) return "INVALID_INPUT";
        if (statusCode.value() == HttpStatus.NOT_FOUND.value()) return "NOT_FOUND";
        if (statusCode.value() == HttpStatus.METHOD_NOT_ALLOWED.value()) return "METHOD_NOT_ALLOWED";
        if (statusCode.value() == HttpStatus.UNSUPPORTED_MEDIA_TYPE.value()) return "UNSUPPORTED_MEDIA_TYPE";
        if (statusCode.is4xxClientError()) return "CLIENT_ERROR";
        return "INTERNAL_SERVER_ERROR";
    }

    private String clientSafeMessage(Exception ex, HttpStatusCode statusCode) {
        if (statusCode.is5xxServerError()) {
            return "An unexpected error occurred. Please try again later.";
        }
        // 요청 형식 오류는 원인을 알려주는 편이 클라이언트에게 유용하고, 내부 구조를 드러내지 않는다.
        return ex.getMessage() != null ? ex.getMessage() : "Request could not be processed.";
    }

    /**
     * 도메인 입력 검증 실패 (HTTP 400)
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException e) {
        log.warn("Invalid input: {}", e.getMessage());

        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("INVALID_INPUT", e.getMessage()));
    }

    /**
     * 인증 주체가 해당 리소스에 접근할 권한이 없음 (HTTP 403)
     *
     * <p>이 핸들러가 없으면 컨트롤러에서 던진 {@code AccessDeniedException} 을 아래의 캐치올이
     * 먼저 잡아 <b>권한 오류가 500 으로 나갑니다.</b> Spring Security 의
     * {@code ExceptionTranslationFilter} 는 예외 핸들러 리졸버보다 바깥에 있어서 기회를 얻지 못합니다.
     */
    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(
            org.springframework.security.access.AccessDeniedException e) {
        log.warn("Access denied: {}", e.getMessage());

        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(new ErrorResponse("FORBIDDEN", "You are not allowed to access this resource."));
    }

    /**
     * 인증되지 않은 요청 (HTTP 401)
     */
    @ExceptionHandler(org.springframework.security.core.AuthenticationException.class)
    public ResponseEntity<ErrorResponse> handleAuthentication(
            org.springframework.security.core.AuthenticationException e) {
        log.warn("Authentication failed: {}", e.getMessage());

        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(new ErrorResponse("UNAUTHORIZED", "Authentication is required."));
    }

    /**
     * 계좌를 찾을 수 없음 (HTTP 404)
     */
    @ExceptionHandler(AccountNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleAccountNotFound(AccountNotFoundException e) {
        log.warn("Account not found: {}", e.getMessage());

        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse("ACCOUNT_NOT_FOUND", "The requested account does not exist."));
    }

    /**
     * 동시성 제어 실패 시 발생 (HTTP 409)
     */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ErrorResponse> handleOptimisticLockingFailure(OptimisticLockingFailureException e) {
        log.warn("Concurrency conflict detected. Transaction requires retry.", e);

        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("CONCURRENCY_CONFLICT", "The asset state has been modified by another transaction."));
    }

    /**
     * 잔고 부족 (HTTP 409)
     *
     * <p>입력 형식 오류가 아니라 계좌 상태와의 충돌이므로 400 이 아닌 409 로 구분합니다.
     * 그래야 클라이언트가 "요청을 고쳐야 하는 경우"와 "잔고를 채워야 하는 경우"를 구분할 수 있습니다.
     */
    @ExceptionHandler(InsufficientBalanceException.class)
    public ResponseEntity<ErrorResponse> handleInsufficientBalance(InsufficientBalanceException e) {
        log.warn("Insufficient balance: {}", e.getMessage());

        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("INSUFFICIENT_BALANCE", e.getMessage()));
    }

    /**
     * 거래 대금이 통화 최소 단위보다 작음 (HTTP 422)
     */
    @ExceptionHandler(BelowMinimumNotionalException.class)
    public ResponseEntity<ErrorResponse> handleBelowMinimumNotional(BelowMinimumNotionalException e) {
        log.warn("Below minimum notional: {}", e.getMessage());

        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT)
                .body(new ErrorResponse("BELOW_MINIMUM_NOTIONAL", e.getMessage()));
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
     * 환율 데이터를 신뢰할 수 없어 거래를 차단한 경우 (HTTP 503)
     *
     * <p>서버 결함이 아니라 외부 시세를 확보하지 못해 <b>의도적으로</b> 거래를 막은 상황입니다.
     * 500 으로 내리면 클라이언트가 재시도해도 무의미한 오류로 오인합니다.
     */
    @ExceptionHandler(ArbitrageRiskException.class)
    public ResponseEntity<ErrorResponse> handleArbitrageRisk(ArbitrageRiskException e) {
        log.error("Trade blocked due to unreliable exchange rate: {}", e.getMessage());

        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ErrorResponse("EXCHANGE_RATE_UNAVAILABLE",
                        "Exchange rate data is not reliable enough to process this trade. Please retry shortly."));
    }

    /**
     * 시세 공급자 장애로 시세를 전혀 확보할 수 없는 경우 (HTTP 503)
     *
     * <p>외부 의존성 장애이므로 재시도가 의미 있는 상황입니다. 이전에는 이 경로가
     * {@code IllegalStateException} 으로 올라와 422 로 나갔는데, 그러면 클라이언트가 자기 요청이
     * 잘못된 것으로 오인해 재시도를 포기합니다.
     */
    @ExceptionHandler(MarketDataUnavailableException.class)
    public ResponseEntity<ErrorResponse> handleMarketDataUnavailable(MarketDataUnavailableException e) {
        log.error("Market data unavailable: {}", e.getMessage());

        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ErrorResponse("MARKET_DATA_UNAVAILABLE",
                        "Market data is temporarily unavailable. Please retry shortly."));
    }

    /**
     * 시세를 제공할 공급자가 없는 자산 코드 (HTTP 422)
     *
     * <p>재시도해도 결과가 달라지지 않는 <b>영구</b> 오류이므로 503 과 구분합니다. 그래야
     * 클라이언트가 "잠시 후 재시도"와 "이 자산은 거래할 수 없음"을 구분할 수 있습니다.
     * 메시지는 어떤 자산군이 왜 불가한지 알려주는 편이 유용하고 내부 구조를 드러내지 않습니다.
     */
    @ExceptionHandler(UnsupportedAssetCodeException.class)
    public ResponseEntity<ErrorResponse> handleUnsupportedAssetCode(UnsupportedAssetCodeException e) {
        log.warn("Unsupported asset code: {}", e.getMessage());

        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT)
                .body(new ErrorResponse("UNSUPPORTED_ASSET", e.getMessage()));
    }

    /**
     * 대차 불일치 (HTTP 500)
     */
    @ExceptionHandler(DoubleEntryImbalanceException.class)
    public ResponseEntity<ErrorResponse> handleDoubleEntryImbalance(DoubleEntryImbalanceException e) {
        log.error("CRITICAL: Double-entry imbalance detected in ledger!", e);

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse("SYSTEM_CRITICAL_ERROR", "A fatal system error occurred while processing the ledger."));
    }

    /**
     * 도메인 상태 위반 (HTTP 422)
     *
     * <p>메시지는 인프라 내부 사정(누락된 원장, 초기화 실패 등)을 담을 수 있으므로
     * 클라이언트에게 그대로 노출하지 않고 로그에만 남깁니다.
     */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ErrorResponse> handleIllegalState(IllegalStateException e) {
        log.error("Domain rule violation: {}", e.getMessage(), e);

        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT)
                .body(new ErrorResponse("DOMAIN_RULE_VIOLATION", "The request could not be processed in the current state."));
    }

    /**
     * 처리되지 않은 모든 서버 내부 예외(HTTP 500)
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnhandledException(Exception e) {
        log.error("Unhandled exception occurred: ", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse("INTERNAL_SERVER_ERROR", "An unexpected error occurred. Please try again later."));
    }
}
