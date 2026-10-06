package com.github.raonjena99.multi_currency_ledger_service.account.application;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.github.raonjena99.multi_currency_ledger_service.account.domain.Account;
import com.github.raonjena99.multi_currency_ledger_service.account.domain.AccountStatus;
import com.github.raonjena99.multi_currency_ledger_service.account.domain.AccountStatusHistory;
import com.github.raonjena99.multi_currency_ledger_service.account.infrastructure.AccountRepository;
import com.github.raonjena99.multi_currency_ledger_service.account.infrastructure.AccountStatusHistoryRepository;
import com.github.raonjena99.multi_currency_ledger_service.account.infrastructure.MonthlyAccountLedgerRepository;
import com.github.raonjena99.multi_currency_ledger_service.common.exception.AccountNotEmptyException;
import com.github.raonjena99.multi_currency_ledger_service.common.exception.AccountNotFoundException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 계좌를 정지·해제·해지하고, 변경마다 이력을 남깁니다.
 *
 * <p>상태 변경은 계좌 행을 배타 잠금으로 읽습니다. 거래·입출금은 같은 행을 공유 잠금으로 읽으므로
 * ({@code AccountTradeService#requireActiveAccount}) 둘이 동시에 진행되지 않습니다. 잠금이 없으면
 * 해지가 잔고 0 을 보고 진행하는 사이, 계좌를 정상으로 읽어 둔 입금이 해지 뒤에 커밋되어
 * 잔고가 남은 해지 계좌가 생깁니다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccountStatusService {

    private final AccountRepository accountRepository;
    private final AccountStatusHistoryRepository historyRepository;
    private final MonthlyAccountLedgerRepository ledgerRepository;

    /**
     * 계좌를 정지합니다. 정지된 계좌는 매수·매도·입출금이 거부됩니다.
     */
    @Transactional
    public void suspend(UUID accountId, String reason, String changedBy) {
        change(accountId, reason, changedBy, Account::suspend);
    }

    /**
     * 정지된 계좌를 정상으로 되돌립니다.
     */
    @Transactional
    public void activate(UUID accountId, String reason, String changedBy) {
        change(accountId, reason, changedBy, Account::activate);
    }

    /**
     * 계좌를 해지합니다. 해지된 계좌는 되살리지 않습니다.
     *
     * @throws AccountNotEmptyException 잔고가 남은 자산이 있는 경우. 음수 잔고(고객 채권)도 정산 전이므로 거부합니다.
     */
    @Transactional
    public void close(UUID accountId, String reason, String changedBy) {
        change(accountId, reason, changedBy, account -> {
            account.close();
            // 배타 잠금을 잡은 뒤에 잔고를 읽어야 진행 중이던 입금의 결과까지 본다.
            boolean hasBalance = ledgerRepository.findLatestBalancesByAccountId(accountId).stream()
                    .anyMatch(ledger -> !ledger.getBalance().isZero());
            if (hasBalance) {
                throw new AccountNotEmptyException(accountId);
            }
        });
    }

    /**
     * 계좌를 조회합니다.
     */
    @Transactional(readOnly = true)
    public Account findAccount(UUID accountId) {
        return accountRepository.findById(accountId).orElseThrow(() -> new AccountNotFoundException(accountId));
    }

    /**
     * 계좌의 상태 변경 이력을 오래된 순으로 조회합니다.
     */
    @Transactional(readOnly = true)
    public List<AccountStatusHistory> history(UUID accountId) {
        if (!accountRepository.existsById(accountId)) {
            throw new AccountNotFoundException(accountId);
        }
        return historyRepository.findByAccountIdOrderByIdAsc(accountId);
    }

    private void change(UUID accountId, String reason, String changedBy, Consumer<Account> transition) {
        Account account = accountRepository.findByIdForUpdate(accountId)
                .orElseThrow(() -> new AccountNotFoundException(accountId));
        AccountStatus from = account.getStatus();

        transition.accept(account);

        historyRepository.save(AccountStatusHistory.record(accountId, from, account.getStatus(), reason, changedBy));
        log.info("Account status changed. accountId={}, {} -> {}, by={}, reason={}",
                accountId, from, account.getStatus(), changedBy, reason);
    }
}
