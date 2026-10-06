package com.github.raonjena99.multi_currency_ledger_service.account.presentation;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.github.raonjena99.multi_currency_ledger_service.account.application.AccountStatusService;
import com.github.raonjena99.multi_currency_ledger_service.account.domain.Account;
import com.github.raonjena99.multi_currency_ledger_service.account.domain.AccountStatus;
import com.github.raonjena99.multi_currency_ledger_service.common.security.AccountOwnershipGuard;

import lombok.RequiredArgsConstructor;

/**
 * 계좌 정보(기준 통화, 상태)를 조회하는 REST API 컨트롤러입니다. 잔고는 포트폴리오 API 로 조회합니다.
 */
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

    @GetMapping
    public ResponseEntity<AccountResponse> getAccount(@PathVariable UUID accountId) {
        ownershipGuard.requireOwnership(accountId);
        return ResponseEntity.ok(AccountResponse.from(statusService.findAccount(accountId)));
    }
}
