package com.github.raonjena99.multi_currency_ledger_service.account.presentation;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import com.github.raonjena99.multi_currency_ledger_service.account.application.AccountStatusService;
import com.github.raonjena99.multi_currency_ledger_service.account.domain.Account;
import com.github.raonjena99.multi_currency_ledger_service.account.domain.AccountStatus;
import com.github.raonjena99.multi_currency_ledger_service.common.security.AccountOwnershipGuard;

import lombok.RequiredArgsConstructor;

/**
 * 계좌 정보(기준 통화, 상태)를 조회하는 REST API 컨트롤러입니다. 잔고는 포트폴리오 API 로 조회합니다.
 */
@Tag(name = "계좌", description = "계좌 정보. 계좌 소유자")
@RestController
@RequestMapping("/api/v1/accounts/{accountId}")
@RequiredArgsConstructor
public class AccountQueryController {

    private final AccountStatusService statusService;
    private final AccountOwnershipGuard ownershipGuard;

    public record AccountResponse(UUID accountId, String ownerName, String baseCurrency, AccountStatus status) {
        static AccountResponse from(Account account) {
            return new AccountResponse(account.getId(), account.getOwnerName(), account.getBaseCurrency(),
                    account.getStatus());
        }
    }

    @Operation(summary = "계좌 정보 조회 (기준 통화, 상태)")
    @ApiResponse(responseCode = "200", description = "성공")
    @ApiResponse(responseCode = "404", description = "계좌 없음 (ACCOUNT_NOT_FOUND)")
    @GetMapping
    public ResponseEntity<AccountResponse> getAccount(@PathVariable UUID accountId) {
        ownershipGuard.requireOwnership(accountId);
        return ResponseEntity.ok(AccountResponse.from(statusService.findAccount(accountId)));
    }
}
