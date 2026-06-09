package com.github.raonjena99.multi_currency_ledger_service.account.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.transaction.annotation.Transactional;

import com.github.raonjena99.multi_currency_ledger_service.IntegrationTestSupport;
import com.github.raonjena99.multi_currency_ledger_service.account.domain.Account;
import com.github.raonjena99.multi_currency_ledger_service.account.domain.AccountBalance;
import com.github.raonjena99.multi_currency_ledger_service.account.domain.event.TradeExecutedEvent;
import com.github.raonjena99.multi_currency_ledger_service.account.infrastructure.AccountBalanceRepository;
import com.github.raonjena99.multi_currency_ledger_service.account.infrastructure.AccountRepository;
import com.github.raonjena99.multi_currency_ledger_service.common.domain.Money;
import com.github.raonjena99.multi_currency_ledger_service.common.model.AssetType;

@Transactional
@RecordApplicationEvents
@DisplayName("서비스 통합 테스트: AccountTradeService (실제 흐름 및 이벤트 발행 검증)")
class AccountTradeServiceTest extends IntegrationTestSupport {

    @Autowired
    private AccountTradeService accountTradeService;

    @Autowired
    private AccountBalanceRepository accountBalanceRepository;

    @Autowired 
    private AccountRepository accountRepository;

    @Autowired
    private ApplicationEvents applicationEvents;

    @Test
    @DisplayName("자산 매수 시 원화 잔액 차감, 자산 증가, 그리고 도메인 이벤트가 정상 발행된다.")
    void buyAsset_integration_flow() {
        // given
        UUID accountId = UUID.randomUUID();

        accountRepository.save(new Account(accountId, "TEST_USER"));

        // 초기 현금 지급 (5천만 원)
        AccountBalance fiatBalance = new AccountBalance(accountId, "KRW", AssetType.FIAT);
        fiatBalance.addBalance(Money.of("50000000", AssetType.FIAT), Money.of("1", AssetType.FIAT));
        accountBalanceRepository.save(fiatBalance);

        // 1 BTC 당 1억 원 * 0.5개 매수 = 5천만 원 소모
        Money buyQuantity = Money.of("0.5", AssetType.CRYPTO);
        Money unitPrice = Money.of("100000000", AssetType.FIAT); 

        // when
        UUID tradeId = accountTradeService.buyAsset(accountId, "BTC", AssetType.CRYPTO, buyQuantity, unitPrice);

        // then
        // 1. DB 잔액 검증
        AccountBalance updatedFiat = accountBalanceRepository.findByAccountIdAndAssetCode(accountId, "KRW").orElseThrow();
        AccountBalance updatedCrypto = accountBalanceRepository.findByAccountIdAndAssetCode(accountId, "BTC").orElseThrow();

        assertThat(updatedFiat.getBalance().getAmount()).isEqualByComparingTo("0"); 
        assertThat(updatedCrypto.getBalance().getAmount()).isEqualByComparingTo("0.5");
        
        // 2. 부패 방지 계층(ACL)으로 릴레이될 도메인 이벤트 발행 확인
        long eventCount = applicationEvents.stream(TradeExecutedEvent.class)
            .filter(event -> event.tradeId().equals(tradeId))
            .count();
        
        assertThat(eventCount).isEqualTo(1);
    }
}