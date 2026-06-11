package com.github.raonjena99.multi_currency_ledger_service.account.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.transaction.support.TransactionTemplate;

import com.github.raonjena99.multi_currency_ledger_service.IntegrationTestSupport;
import com.github.raonjena99.multi_currency_ledger_service.account.domain.Account;
import com.github.raonjena99.multi_currency_ledger_service.account.domain.MonthlyAccountLedger;
import com.github.raonjena99.multi_currency_ledger_service.account.domain.event.TradeExecutedEvent;
import com.github.raonjena99.multi_currency_ledger_service.account.infrastructure.AccountRepository;
import com.github.raonjena99.multi_currency_ledger_service.account.infrastructure.MonthlyAccountLedgerRepository;
import com.github.raonjena99.multi_currency_ledger_service.common.domain.Money;
import com.github.raonjena99.multi_currency_ledger_service.common.model.AssetType;

@RecordApplicationEvents
@DisplayName("서비스 통합 테스트: AccountTradeService (실제 흐름 및 이벤트 발행 검증)")
class AccountTradeServiceTest extends IntegrationTestSupport {

    @Autowired private AccountTradeService accountTradeService;
    @Autowired private MonthlyAccountLedgerRepository monthlyAccountLedgerRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private ApplicationEvents applicationEvents;
    
    @Autowired private TransactionTemplate transactionTemplate;

    @AfterEach
    void tearDown() {
        monthlyAccountLedgerRepository.deleteAllInBatch();
        accountRepository.deleteAllInBatch();
    }

    @Test
    @DisplayName("자산 매수 시 원화 잔액 차감, 자산 증가, 그리고 도메인 이벤트가 정상 발행된다.")
    void buyAsset_integration_flow() {
        // given
        UUID accountId = UUID.randomUUID();
        String currentMonth = OffsetDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM"));

        transactionTemplate.execute(status -> {
            accountRepository.save(new Account(accountId, "TEST_USER"));
            MonthlyAccountLedger fiatLedger = new MonthlyAccountLedger(accountId, "KRW", AssetType.FIAT, currentMonth);
            fiatLedger.addBalance(Money.of("50000000", AssetType.FIAT), Money.of("1", AssetType.FIAT));
            monthlyAccountLedgerRepository.save(fiatLedger);
            return null;
        });

        Money buyQuantity = Money.of("0.5", AssetType.CRYPTO);
        Money unitPrice = Money.of("100000000", AssetType.FIAT); 

        // when
        UUID tradeId = accountTradeService.buyAsset(accountId, "BTC", AssetType.CRYPTO, buyQuantity, unitPrice);

        // then
        transactionTemplate.execute(status -> {
            MonthlyAccountLedger updatedFiat = monthlyAccountLedgerRepository.findByAccountIdAndAssetCodeAndLedgerMonth(accountId, "KRW", currentMonth).orElseThrow();
            MonthlyAccountLedger updatedCrypto = monthlyAccountLedgerRepository.findByAccountIdAndAssetCodeAndLedgerMonth(accountId, "BTC", currentMonth).orElseThrow();

            assertThat(updatedFiat.getBalance().getAmount()).isEqualByComparingTo("0"); 
            assertThat(updatedCrypto.getBalance().getAmount()).isEqualByComparingTo("0.5");
            return null;
        });
        
        long eventCount = applicationEvents.stream(TradeExecutedEvent.class)
            .filter(event -> event.tradeId().equals(tradeId))
            .count();
        
        assertThat(eventCount).isEqualTo(1);
    }
}