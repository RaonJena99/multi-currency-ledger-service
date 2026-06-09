// 파일 경로: src/test/java/com/github/raonjena99/multi_currency_ledger_service/account/application/AccountTradeConcurrencyTest.java
package com.github.raonjena99.multi_currency_ledger_service.account.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import com.github.raonjena99.multi_currency_ledger_service.IntegrationTestSupport;
import com.github.raonjena99.multi_currency_ledger_service.account.domain.Account;
import com.github.raonjena99.multi_currency_ledger_service.account.domain.AccountBalance;
import com.github.raonjena99.multi_currency_ledger_service.account.infrastructure.AccountBalanceRepository;
import com.github.raonjena99.multi_currency_ledger_service.account.infrastructure.AccountRepository;
import com.github.raonjena99.multi_currency_ledger_service.common.domain.Money;
import com.github.raonjena99.multi_currency_ledger_service.common.model.AssetType;

@DisplayName("동시성 테스트: AccountTradeService (낙관적 락 @Version 검증)")
class AccountTradeConcurrencyTest extends IntegrationTestSupport {

    @Autowired private AccountTradeService accountTradeService;
    @Autowired private AccountBalanceRepository accountBalanceRepository;
    @Autowired private AccountRepository accountRepository;

    @Test
    @DisplayName("동일한 계좌에 동시에 여러 매수 요청이 들어오면, 낙관적 락이 발생하여 갱신 손실(Lost Update)을 방지한다.")
    void concurrent_buy_asset_triggers_optimistic_lock() throws InterruptedException {
        // given
        UUID accountId = UUID.randomUUID();
        
        // [수정] 즉시 DB에 반영하여 FK 위반 방지
        accountRepository.saveAndFlush(new Account(accountId, "TEST_USER"));

        AccountBalance fiatBalance = new AccountBalance(accountId, "KRW", AssetType.FIAT);
        fiatBalance.addBalance(Money.of("10000000000", AssetType.FIAT), Money.of("1", AssetType.FIAT));
        accountBalanceRepository.saveAndFlush(fiatBalance);

        // [핵심 수정] 5개의 스레드가 INSERT(UNIQUE 충돌)가 아닌 UPDATE(낙관적 락 충돌)를 수행하도록 대상 자산 미리 생성
        AccountBalance btcBalance = new AccountBalance(accountId, "BTC", AssetType.CRYPTO);
        accountBalanceRepository.saveAndFlush(btcBalance);

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
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();

        // then
        assertThat(successCount.get()).isEqualTo(1);
        assertThat(lockExceptionCount.get()).isEqualTo(4);

        AccountBalance fetchedBtcBalance = accountBalanceRepository.readByAccountIdAndAssetCode(accountId, "BTC").orElseThrow();
        assertThat(fetchedBtcBalance.getBalance().getAmount()).isEqualByComparingTo("1");
    }
}