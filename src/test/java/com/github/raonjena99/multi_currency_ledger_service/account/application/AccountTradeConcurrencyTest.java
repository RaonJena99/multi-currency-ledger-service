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
    
    @Autowired private TransactionTemplate transactionTemplate;

    @AfterEach
    void tearDown() {
        monthlyAccountLedgerRepository.deleteAllInBatch();
        accountRepository.deleteAllInBatch();
    }

    @Test
    @DisplayName("동일한 계좌에 동시에 여러 매수 요청이 들어오면, 낙관적 락이 발생하여 갱신 손실(Lost Update)을 방지한다.")
    void concurrent_buy_asset_triggers_optimistic_lock() throws InterruptedException {
        // given
        UUID accountId = UUID.randomUUID();
        String currentMonth = OffsetDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM"));

        transactionTemplate.execute(status -> {
            accountRepository.save(new Account(accountId, "TEST_USER"));

            MonthlyAccountLedger fiatLedger = new MonthlyAccountLedger(accountId, "KRW", AssetType.FIAT, currentMonth);
            fiatLedger.addBalance(Money.of("10000000000", AssetType.FIAT), Money.of("1", AssetType.FIAT));
            monthlyAccountLedgerRepository.save(fiatLedger);

            MonthlyAccountLedger btcLedger = new MonthlyAccountLedger(accountId, "BTC", AssetType.CRYPTO, currentMonth);
            monthlyAccountLedgerRepository.save(btcLedger);
            return null;
        });

        int threadCount = 5;
        ExecutorService executorService = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger lockExceptionCount = new AtomicInteger(0);

        Money buyQuantity = Money.of("1", AssetType.CRYPTO);
        Money unitPrice = Money.of("50000000", AssetType.FIAT);

        // when
        for (int i = 0; i < threadCount; i++) {
            executorService.execute(() -> {
                try {
                    accountTradeService.buyAsset(accountId, "BTC", AssetType.CRYPTO, buyQuantity, unitPrice);
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
        assertThat(successCount.get()).isEqualTo(1);
        assertThat(lockExceptionCount.get()).isEqualTo(4);

        transactionTemplate.execute(status -> {
            MonthlyAccountLedger fetchedBtcLedger = monthlyAccountLedgerRepository.findByAccountIdAndAssetCodeAndLedgerMonth(accountId, "BTC", currentMonth).orElseThrow();
            assertThat(fetchedBtcLedger.getBalance().getAmount()).isEqualByComparingTo("1");
            return null;
        });
    }
}