package com.github.raonjena99.multi_currency_ledger_service.account.presentation;

import java.math.BigDecimal;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.github.raonjena99.multi_currency_ledger_service.account.application.AccountOpeningService;
import com.github.raonjena99.multi_currency_ledger_service.account.application.AccountTradeFacade;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;

/**
 * 계좌 개설과 법정화폐 입출금을 위한 관리자·내부 시스템용 REST API 컨트롤러입니다.
 *
 * <p>고객이 직접 호출하면 안 되는 API 라서 {@code /api/v1/admin} 아래에 둡니다.
 * <ul>
 *   <li>개설: 소유권은 게이트웨이가 {@code X-Auth-Account-Id} 로 정하므로, 계좌 ID 와 고객의 연결을
 *       관리하는 쪽이 개설해야 합니다.</li>
 *   <li>입출금: 고객이 입금을 직접 호출할 수 있으면 실제 송금 없이 잔고를 늘릴 수 있습니다.
 *       결제·송금 확인을 마친 쪽이 호출해야 합니다.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/admin/accounts")
@RequiredArgsConstructor
public class AccountAdminController {

    private final AccountOpeningService openingService;
    private final AccountTradeFacade tradeFacade;

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

    @PostMapping
    public ResponseEntity<OpenAccountResponse> openAccount(@Valid @RequestBody OpenAccountRequest request) {
        openingService.open(request.accountId(), request.ownerName(), request.baseCurrency());
        return ResponseEntity.status(HttpStatus.CREATED).body(new OpenAccountResponse(request.accountId()));
    }

    @PostMapping("/{accountId}/deposits")
    public ResponseEntity<CashTransferResponse> deposit(
            @PathVariable UUID accountId,
            @Valid @RequestBody CashTransferRequest request) {
        UUID transactionId = tradeFacade.deposit(
                request.idempotencyKey(), accountId, request.currency(), request.amount());
        return ResponseEntity.ok(new CashTransferResponse(transactionId));
    }

    @PostMapping("/{accountId}/withdrawals")
    public ResponseEntity<CashTransferResponse> withdraw(
            @PathVariable UUID accountId,
            @Valid @RequestBody CashTransferRequest request) {
        UUID transactionId = tradeFacade.withdraw(
                request.idempotencyKey(), accountId, request.currency(), request.amount());
        return ResponseEntity.ok(new CashTransferResponse(transactionId));
    }
}
