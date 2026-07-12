package com.github.raonjena99.multi_currency_ledger_service.account.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.support.TransactionTemplate;

import com.github.raonjena99.multi_currency_ledger_service.IntegrationTestSupport;
import com.github.raonjena99.multi_currency_ledger_service.account.domain.Account;
import com.github.raonjena99.multi_currency_ledger_service.account.domain.MonthlyAccountLedger;
import com.github.raonjena99.multi_currency_ledger_service.account.infrastructure.AccountRepository;
import com.github.raonjena99.multi_currency_ledger_service.account.infrastructure.MonthlyAccountLedgerRepository;
import com.github.raonjena99.multi_currency_ledger_service.common.domain.Money;
import com.github.raonjena99.multi_currency_ledger_service.common.model.AssetType;

@DisplayName("동시성 테스트: AccountTradeService (낙관적 락 @Version 검증)")
class AccountTradeConcurrencyTest extends IntegrationTestSupport {

    @Autowired private AccountTradeService accountTradeService;
    @Autowired private MonthlyAccountLedgerRepository monthlyAccountLedgerRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;
    
    @Autowired private TransactionTemplate transactionTemplate;

    @AfterEach
    void tearDown() {
        jdbcTemplate.execute("TRUNCATE TABLE outbox_events, transactions, transaction_entries, monthly_account_ledgers, accounts CASCADE");
    }

    @Test
    @DisplayName("동일한 계좌에 동시에 여러 매수 요청이 들어오면, 낙관적 락이 발생하여 갱신 손실(Lost Update)을 방지한다.")
    void concurrent_buy_asset_triggers_optimistic_lock() throws InterruptedException {
        // given
        UUID accountId = UUID.randomUUID();
        String currentMonth = OffsetDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM"));

        transactionTemplate.execute(status -> {
            accountRepository.save(Account.open(accountId, "TEST_USER", "KRW"));

            MonthlyAccountLedger fiatLedger = MonthlyAccountLedger.initialize(accountId, "KRW", AssetType.FIAT, currentMonth, "KRW");
            fiatLedger.addBalance(Money.of("10000000000", AssetType.FIAT, "KRW"), Money.of("1", AssetType.FIAT, "KRW"));
            monthlyAccountLedgerRepository.save(fiatLedger);

            MonthlyAccountLedger btcLedger = MonthlyAccountLedger.initialize(accountId, "BTC", AssetType.CRYPTO, currentMonth, "KRW");
            monthlyAccountLedgerRepository.save(btcLedger);
            return null;
        });

        int threadCount = 5;
        ExecutorService executorService = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger lockExceptionCount = new AtomicInteger(0);

        Money buyQuantity = Money.of("1", AssetType.CRYPTO, "BTC");
        Money unitPrice = Money.of("50000000", AssetType.FIAT, "KRW");

        // when
        for (int i = 0; i < threadCount; i++) {
            executorService.execute(() -> {
                try {
                    accountTradeService.buyAsset(UUID.randomUUID().toString(), accountId, "BTC", AssetType.CRYPTO, "KRW", buyQuantity, unitPrice);
                    successCount.incrementAndGet();
                } catch (ObjectOptimisticLockingFailureException e) {
                    lockExceptionCount.incrementAndGet();
                } catch (Exception e) {
                    // ignore
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();

        // then
        // @Retryable 적용으로 인해 재시도가 발생하지만, maxAttempts(3) 초과 시 실패할 수 있으므로
        // 성공한 횟수(successCount)만큼 잔고가 정확히 증가했는지(Lost Update가 없는지) 검증합니다.
        int actualSuccesses = successCount.get();
        assertThat(actualSuccesses).isGreaterThanOrEqualTo(1);

        transactionTemplate.execute(status -> {
            MonthlyAccountLedger fetchedBtcLedger = monthlyAccountLedgerRepository.findByAccountIdAndAssetCodeAndLedgerMonth(accountId, "BTC", currentMonth).orElseThrow();
            assertThat(fetchedBtcLedger.getBalance().getAmount()).isEqualByComparingTo(String.valueOf(actualSuccesses));
            return null;
        });
    }
}