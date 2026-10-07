package com.github.raonjena99.multi_currency_ledger_service.account.presentation;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import com.github.raonjena99.multi_currency_ledger_service.account.application.AccountOpeningService;
import com.github.raonjena99.multi_currency_ledger_service.account.application.AccountStatusService;
import com.github.raonjena99.multi_currency_ledger_service.account.application.AccountTradeFacade;
import com.github.raonjena99.multi_currency_ledger_service.account.domain.AccountStatus;
import com.github.raonjena99.multi_currency_ledger_service.account.domain.AccountStatusHistory;
import com.github.raonjena99.multi_currency_ledger_service.common.security.LedgerPrincipal;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;

/**
 * 계좌 개설·상태 관리와 법정화폐 입출금을 위한 관리자·내부 시스템용 REST API 컨트롤러입니다.
 *
 * <p>고객이 직접 호출하면 안 되는 API 라서 {@code /api/v1/admin} 아래에 둡니다.
 * <ul>
 *   <li>개설: 소유권은 게이트웨이가 {@code X-Auth-Account-Id} 로 정하므로, 계좌 ID 와 고객의 연결을
 *       관리하는 쪽이 개설해야 합니다.</li>
 *   <li>입출금: 고객이 입금을 직접 호출할 수 있으면 실제 송금 없이 잔고를 늘릴 수 있습니다.
 *       결제·송금 확인을 마친 쪽이 호출해야 합니다.</li>
 * </ul>
 */
@Tag(name = "계좌 관리 (관리자)", description = "계좌 개설·상태 관리·법정화폐 입출금. 관리자·내부 시스템 전용")
@RestController
@RequestMapping("/api/v1/admin/accounts")
@RequiredArgsConstructor
public class AccountAdminController {

    private final AccountOpeningService openingService;
    private final AccountTradeFacade tradeFacade;
    private final AccountStatusService statusService;

    /**
     * 계좌 개설 요청 본문입니다.
     *
     * @param accountId    호출한 쪽이 정한 계좌 ID
     * @param ownerName    소유자 이름 (accounts.owner_name 은 100자)
     * @param baseCurrency 기준 통화 (ISO 4217, accounts.base_currency 는 3자)
     */
    public record OpenAccountRequest(
            @NotNull UUID accountId,
            @NotBlank @Size(max = 100) String ownerName,
            @NotBlank @Size(max = 3) String baseCurrency) {
    }

    public record OpenAccountResponse(UUID accountId) {}

    /**
     * 입출금 요청 본문입니다. 멱등성 키와 금액 한도는 매수·매도 요청과 같은 기준을 따릅니다.
     *
     * @param idempotencyKey 중복 요청 방지 키
     * @param currency       입출금 통화 (ISO 4217)
     * @param amount         입출금액
     */
    public record CashTransferRequest(
            @NotBlank @Size(max = 128) String idempotencyKey,
            @NotBlank @Size(max = 10) String currency,
            @NotNull @Positive @Digits(integer = 18, fraction = 100) BigDecimal amount) {

        public CashTransferRequest {
            // 매수·매도 요청과 같이 통화 코드를 정규화해 같은 통화가 다른 원장 행으로 갈라지지 않게 한다.
            if (currency != null) {
                currency = currency.trim().toUpperCase();
            }
        }
    }

    public record CashTransferResponse(UUID transactionId) {}

    /** 정지 요청 본문. 나중에 해제할 근거가 되므로 사유가 필수입니다. */
    public record SuspendRequest(@NotBlank @Size(max = 500) String reason) {}

    /** 해제·해지 요청 본문. 사유는 선택입니다. */
    public record StatusChangeRequest(@Size(max = 500) String reason) {}

    public record StatusChangeResponse(AccountStatus fromStatus, AccountStatus toStatus, String reason,
                                       String changedBy, OffsetDateTime changedAt) {
        static StatusChangeResponse from(AccountStatusHistory history) {
            return new StatusChangeResponse(history.getFromStatus(), history.getToStatus(), history.getReason(),
                    history.getChangedBy(), history.getChangedAt());
        }
    }

    @Operation(summary = "계좌 개설")
    @ApiResponse(responseCode = "201", description = "개설됨")
    @ApiResponse(responseCode = "409", description = "이미 있는 계좌 ID (ACCOUNT_ALREADY_EXISTS)")
    @ApiResponse(responseCode = "422", description = "기준 통화가 법정화폐(ISO 4217) 코드가 아님")
    @PostMapping
    public ResponseEntity<OpenAccountResponse> openAccount(@Valid @RequestBody OpenAccountRequest request) {
        openingService.open(request.accountId(), request.ownerName(), request.baseCurrency());
        return ResponseEntity.status(HttpStatus.CREATED).body(new OpenAccountResponse(request.accountId()));
    }

    @Operation(summary = "법정화폐 입금")
    @ApiResponse(responseCode = "200", description = "성공")
    @ApiResponse(responseCode = "404", description = "계좌 없음 (ACCOUNT_NOT_FOUND)")
    @ApiResponse(responseCode = "409", description = "같은 멱등성 키로 처리 중인 요청 (DUPLICATE_REQUEST), 동시 수정 충돌 (CONCURRENCY_CONFLICT). 둘 다 같은 멱등성 키로 잠시 뒤 재시도하면 됩니다")
    @ApiResponse(responseCode = "422", description = "정지·해지된 계좌, 법정화폐가 아닌 통화")
    @ApiResponse(responseCode = "503", description = "기준 통화가 아닌 통화의 환율을 구할 수 없음")
    @PostMapping("/{accountId}/deposits")
    public ResponseEntity<CashTransferResponse> deposit(
            @PathVariable UUID accountId,
            @Valid @RequestBody CashTransferRequest request) {
        UUID transactionId = tradeFacade.deposit(
                request.idempotencyKey(), accountId, request.currency(), request.amount());
        return ResponseEntity.ok(new CashTransferResponse(transactionId));
    }

    /**
     * 계좌를 정지합니다. 정지된 계좌는 매수·매도·입출금이 422 로 거부됩니다.
     */
    @Operation(summary = "계좌 정지 (사유 필수)")
    @ApiResponse(responseCode = "200", description = "성공")
    @ApiResponse(responseCode = "404", description = "계좌 없음 (ACCOUNT_NOT_FOUND)")
    @ApiResponse(responseCode = "422", description = "이미 정지되었거나 해지된 계좌")
    @PostMapping("/{accountId}/suspend")
    public ResponseEntity<Void> suspend(@PathVariable UUID accountId, @Valid @RequestBody SuspendRequest request) {
        statusService.suspend(accountId, request.reason(), currentSubject());
        return ResponseEntity.ok().build();
    }

    @Operation(summary = "계좌 정지 해제")
    @ApiResponse(responseCode = "200", description = "성공")
    @ApiResponse(responseCode = "404", description = "계좌 없음 (ACCOUNT_NOT_FOUND)")
    @ApiResponse(responseCode = "422", description = "이미 정상이거나 해지된 계좌")
    @PostMapping("/{accountId}/activate")
    public ResponseEntity<Void> activate(@PathVariable UUID accountId, @Valid @RequestBody StatusChangeRequest request) {
        statusService.activate(accountId, request.reason(), currentSubject());
        return ResponseEntity.ok().build();
    }

    /**
     * 계좌를 해지합니다. 모든 자산 잔고가 0 이어야 하며, 해지된 계좌는 되살리지 않습니다.
     */
    @Operation(summary = "계좌 해지")
    @ApiResponse(responseCode = "200", description = "성공")
    @ApiResponse(responseCode = "404", description = "계좌 없음 (ACCOUNT_NOT_FOUND)")
    @ApiResponse(responseCode = "409", description = "잔고가 남아 있음 (ACCOUNT_HAS_BALANCE)")
    @ApiResponse(responseCode = "422", description = "이미 해지된 계좌")
    @PostMapping("/{accountId}/close")
    public ResponseEntity<Void> close(@PathVariable UUID accountId, @Valid @RequestBody StatusChangeRequest request) {
        statusService.close(accountId, request.reason(), currentSubject());
        return ResponseEntity.ok().build();
    }

    @Operation(summary = "계좌 상태 변경 이력")
    @ApiResponse(responseCode = "200", description = "성공")
    @ApiResponse(responseCode = "404", description = "계좌 없음 (ACCOUNT_NOT_FOUND)")
    @GetMapping("/{accountId}/status-history")
    public ResponseEntity<List<StatusChangeResponse>> statusHistory(@PathVariable UUID accountId) {
        return ResponseEntity.ok(statusService.history(accountId).stream().map(StatusChangeResponse::from).toList());
    }

    /** 이력에 남길 처리자. 게이트웨이가 넣어 준 주체 식별자(X-Auth-Subject)입니다. */
    private static String currentSubject() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getPrincipal() instanceof LedgerPrincipal principal
                ? principal.subject()
                : "unknown";
    }

    @Operation(summary = "법정화폐 출금")
    @ApiResponse(responseCode = "200", description = "성공")
    @ApiResponse(responseCode = "404", description = "계좌 없음 (ACCOUNT_NOT_FOUND)")
    @ApiResponse(responseCode = "409", description = "잔고 부족 (INSUFFICIENT_BALANCE), 같은 멱등성 키로 처리 중인 요청 (DUPLICATE_REQUEST), 동시 수정 충돌 (CONCURRENCY_CONFLICT). 뒤의 둘은 같은 멱등성 키로 잠시 뒤 재시도하면 됩니다")
    @ApiResponse(responseCode = "422", description = "정지·해지된 계좌, 법정화폐가 아닌 통화")
    @ApiResponse(responseCode = "503", description = "기준 통화가 아닌 통화의 환율을 구할 수 없음")
    @PostMapping("/{accountId}/withdrawals")
    public ResponseEntity<CashTransferResponse> withdraw(
            @PathVariable UUID accountId,
            @Valid @RequestBody CashTransferRequest request) {
        UUID transactionId = tradeFacade.withdraw(
                request.idempotencyKey(), accountId, request.currency(), request.amount());
        return ResponseEntity.ok(new CashTransferResponse(transactionId));
    }
}
