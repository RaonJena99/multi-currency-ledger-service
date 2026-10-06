package com.github.raonjena99.multi_currency_ledger_service.account.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import com.github.raonjena99.multi_currency_ledger_service.IntegrationTestSupport;
import com.github.raonjena99.multi_currency_ledger_service.account.domain.Account;
import com.github.raonjena99.multi_currency_ledger_service.account.domain.AccountStatus;
import com.github.raonjena99.multi_currency_ledger_service.account.domain.MonthlyAccountLedger;
import com.github.raonjena99.multi_currency_ledger_service.account.infrastructure.AccountRepository;
import com.github.raonjena99.multi_currency_ledger_service.account.infrastructure.MonthlyAccountLedgerRepository;
import com.github.raonjena99.multi_currency_ledger_service.common.domain.Money;
import com.github.raonjena99.multi_currency_ledger_service.common.exception.AccountNotEmptyException;
import com.github.raonjena99.multi_currency_ledger_service.common.exception.InvalidAccountStateException;
import com.github.raonjena99.multi_currency_ledger_service.common.model.AssetType;

/**
 * 해지와 입금이 동시에 일어나도 "잔고가 남은 해지 계좌"가 생기지 않는지 검증합니다.
 *
 * <p>잠금이 없으면 해지는 잔고 0 을 보고 진행하고, 그사이 계좌를 정상으로 읽어 둔 입금이 해지 뒤에
 * 커밋되어 해지된 계좌에 돈이 남습니다. 한쪽 트랜잭션이 잠금을 쥔 채 1.5초 머무는 동안 다른 쪽을 실행해
 * 그 순서를 재현합니다.
 */
@DisplayName("계좌 해지와 입금의 동시 실행")
class AccountCloseConcurrencyTest extends IntegrationTestSupport {

    private static final String MONTH = OffsetDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("yyyy-MM"));

    @Autowired private AccountStatusService statusService;
    @Autowired private AccountTradeFacade tradeFacade;
    @Autowired private AccountRepository accountRepository;
    @Autowired private MonthlyAccountLedgerRepository ledgerRepository;
    @Autowired private TransactionTemplate txTemplate;
    @Autowired private JdbcTemplate jdbcTemplate;

    @AfterEach
    void tearDown() {
        jdbcTemplate.execute("TRUNCATE TABLE account_status_history, outbox_events, transaction_entries, transactions, "
                + "monthly_account_ledgers, idempotency_records CASCADE");
        deleteTestAccounts();
    }

    private UUID openAccount() {
        UUID accountId = UUID.randomUUID();
        accountRepository.save(Account.open(accountId, "RACE_USER", "KRW"));
        return accountId;
    }

    private Thread inBackground(Runnable work, AtomicReference<Throwable> failure) {
        Thread thread = new Thread(() -> {
            try {
                work.run();
            } catch (Throwable t) {
                failure.set(t);
            }
        });
        thread.start();
        return thread;
    }

    private static void pause() {
        try {
            Thread.sleep(1500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    @DisplayName("입금이 진행 중이면 해지는 그 입금이 끝날 때까지 기다렸다가 잔고를 보고 거부한다")
    void closeWaitsForInFlightDeposit() throws Exception {
        UUID accountId = openAccount();
        CountDownLatch depositHoldsLock = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        // 입금 트랜잭션: 계좌를 정상으로 확인한 뒤(공유 잠금) 잔고를 늘리고 커밋한다.
        Thread deposit = inBackground(() -> txTemplate.executeWithoutResult(status -> {
            accountRepository.findByIdForShare(accountId).orElseThrow();
            depositHoldsLock.countDown();
            pause();
            MonthlyAccountLedger krw = MonthlyAccountLedger.initialize(accountId, "KRW", AssetType.FIAT, MONTH, "KRW");
            krw.addBalance(Money.of("1000", AssetType.FIAT, "KRW"), BigDecimal.ONE);
            ledgerRepository.save(krw);
        }), failure);

        depositHoldsLock.await();
        assertThatThrownBy(() -> statusService.close(accountId, "고객 요청", "admin-1"))
                .isInstanceOf(AccountNotEmptyException.class);

        deposit.join();
        assertThat(failure.get()).isNull();
        assertThat(accountRepository.findById(accountId).orElseThrow().getStatus()).isEqualTo(AccountStatus.ACTIVE);
    }

    @Test
    @DisplayName("해지가 진행 중이면 입금은 그 해지가 끝날 때까지 기다렸다가 해지된 계좌로 보고 거부한다")
    void depositWaitsForInFlightClose() throws Exception {
        UUID accountId = openAccount();
        CountDownLatch closeHoldsLock = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        // 해지 트랜잭션: 계좌를 배타 잠금으로 읽고 해지한 뒤 잠시 머물다 커밋한다.
        Thread close = inBackground(() -> txTemplate.executeWithoutResult(status -> {
            accountRepository.findByIdForUpdate(accountId).orElseThrow().close();
            closeHoldsLock.countDown();
            pause();
        }), failure);

        closeHoldsLock.await();
        assertThatThrownBy(() -> tradeFacade.deposit("race-1", accountId, "KRW", new BigDecimal("1000")))
                .isInstanceOf(InvalidAccountStateException.class);

        close.join();
        assertThat(failure.get()).isNull();
        assertThat(ledgerRepository.findLatestBalancesByAccountId(accountId))
                .allSatisfy(ledger -> assertThat(ledger.getBalance().getAmount()).isEqualByComparingTo("0"));
    }
}
