package com.github.raonjena99.multi_currency_ledger_service.account.application;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.github.raonjena99.multi_currency_ledger_service.account.AccountApi;
import com.github.raonjena99.multi_currency_ledger_service.account.domain.Account;
import com.github.raonjena99.multi_currency_ledger_service.account.infrastructure.AccountRepository;

import lombok.RequiredArgsConstructor;

/**
 * 이 클래스는 계좌의 기본 통화 정보를 조회하는 기능을 담당합니다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AccountApiImpl implements AccountApi {

    private final AccountRepository accountRepository;

    private final com.github.raonjena99.multi_currency_ledger_service.account.infrastructure.MonthlyAccountLedgerRepository monthlyAccountLedgerRepository;

    @Override
    public String getBaseCurrency(UUID accountId) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new IllegalArgumentException("Account not found: " + accountId));
        return account.getBaseCurrency();
    }

    @Override
    public java.util.List<AccountBalanceDto> getBalances(UUID accountId) {
        return monthlyAccountLedgerRepository.findLatestBalancesByAccountId(accountId).stream()
                .map(ledger -> new AccountBalanceDto(
                        ledger.getAssetCode(),
                        ledger.getBalance().getAmount(),
                        ledger.getAverageUnitPrice().getAmount(),
                        ledger.getAverageUnitPrice().getCurrencyCode()
                )).toList();
    }
}
