package com.github.raonjena99.multi_currency_ledger_service.account.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.github.raonjena99.multi_currency_ledger_service.account.domain.Account;
import com.github.raonjena99.multi_currency_ledger_service.account.infrastructure.AccountRepository;
import com.github.raonjena99.multi_currency_ledger_service.common.domain.Money;
import com.github.raonjena99.multi_currency_ledger_service.common.exception.InvalidAccountStateException;
import com.github.raonjena99.multi_currency_ledger_service.common.model.AssetType;
import com.github.raonjena99.multi_currency_ledger_service.common.port.ExchangeRateProvider;
import com.github.raonjena99.multi_currency_ledger_service.common.port.ExchangeRateProvider.ExchangeRate;

@ExtendWith(MockitoExtension.class)
class AccountTradeFacadeTest {

    @Mock private MonthlyLedgerResolver ledgerResolver;
    @Mock private AccountTradeService tradeService;
    @Mock private AccountRepository accountRepository;
    @Mock private ExchangeRateProvider exchangeRateProvider;

    @InjectMocks
    private AccountTradeFacade facade;

    @Test
    void buyAsset_should_throw_if_account_not_found() {
        UUID accountId = UUID.randomUUID();
        when(accountRepository.findById(accountId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> facade.buyAsset("idemp", accountId, "BTC", AssetType.CRYPTO, "KRW", null, null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void buyAsset_should_throw_if_account_inactive() {
        UUID accountId = UUID.randomUUID();
        Account account = org.mockito.Mockito.mock(Account.class);
        when(account.isActive()).thenReturn(false);
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));

        assertThatThrownBy(() -> facade.buyAsset("idemp", accountId, "BTC", AssetType.CRYPTO, "KRW", null, null))
            .isInstanceOf(InvalidAccountStateException.class);
    }

    @Test
    void buyAsset_should_call_service_with_rates() {
        UUID accountId = UUID.randomUUID();
        Account account = org.mockito.Mockito.mock(Account.class);
        when(account.isActive()).thenReturn(true);
        when(account.getBaseCurrency()).thenReturn("USD"); // base currency != payment currency
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));

        when(exchangeRateProvider.getExchangeRate("BTC", "KRW")).thenReturn(new ExchangeRate(new BigDecimal("50000000"), false));
        when(exchangeRateProvider.getExchangeRate("KRW", "USD")).thenReturn(new ExchangeRate(new BigDecimal("0.00075"), false));

        Money quantity = Money.of("1", AssetType.CRYPTO, "BTC");
        
        facade.buyAsset("idemp", accountId, "BTC", AssetType.CRYPTO, "KRW", quantity, new BigDecimal("50000000"));

        verify(tradeService).executeBuyAsset(
            eq("idemp"), eq(accountId), eq("BTC"), eq(AssetType.CRYPTO), eq("KRW"), eq(quantity), 
            eq(new BigDecimal("50000000")), any(OffsetDateTime.class), eq(new BigDecimal("50000000")), 
            eq(false), eq(new BigDecimal("0.00075"))
        );
    }

    @Test
    void sellAsset_should_call_service_with_rates_same_base_currency() {
        UUID accountId = UUID.randomUUID();
        Account account = org.mockito.Mockito.mock(Account.class);
        when(account.isActive()).thenReturn(true);
        when(account.getBaseCurrency()).thenReturn("KRW"); // base currency == payment currency
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));

        when(exchangeRateProvider.getExchangeRate("BTC", "KRW")).thenReturn(new ExchangeRate(new BigDecimal("50000000"), true));

        Money quantity = Money.of("1", AssetType.CRYPTO, "BTC");
        
        facade.sellAsset("idemp", accountId, "BTC", AssetType.CRYPTO, "KRW", quantity, new BigDecimal("50000000"));

        verify(tradeService).executeSellAsset(
            eq("idemp"), eq(accountId), eq("BTC"), eq(AssetType.CRYPTO), eq("KRW"), eq(quantity), 
            eq(new BigDecimal("50000000")), any(OffsetDateTime.class), eq(new BigDecimal("50000000")), 
            eq(true), eq(null) // null fiatToBaseRate
        );
    }

    @Test
    void sellAsset_should_throw_if_account_not_found() {
        UUID accountId = UUID.randomUUID();
        when(accountRepository.findById(accountId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> facade.sellAsset("idemp", accountId, "BTC", AssetType.CRYPTO, "KRW", null, null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void sellAsset_should_throw_if_account_inactive() {
        UUID accountId = UUID.randomUUID();
        Account account = org.mockito.Mockito.mock(Account.class);
        when(account.isActive()).thenReturn(false);
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));

        assertThatThrownBy(() -> facade.sellAsset("idemp", accountId, "BTC", AssetType.CRYPTO, "KRW", null, null))
            .isInstanceOf(InvalidAccountStateException.class);
    }
    
    @Test
    @org.junit.jupiter.api.DisplayName("성공: paymentCurrency와 baseCurrency가 다르면 교차 환율 적용")
    void sellAsset_Success_CrossCurrency() {
        // baseCurrency: KRW, paymentCurrency: USD
        UUID accountId = UUID.randomUUID();
        Account account = org.mockito.Mockito.mock(Account.class);
        when(account.isActive()).thenReturn(true);
        when(account.getBaseCurrency()).thenReturn("KRW");
        when(accountRepository.findById(accountId)).thenReturn(java.util.Optional.of(account));
        
        when(exchangeRateProvider.getExchangeRate("BTC", "USD")).thenReturn(
            new ExchangeRateProvider.ExchangeRate(new java.math.BigDecimal("50000"), false)
        );
        // paymentCurrency(USD) -> baseCurrency(KRW) 환율
        when(exchangeRateProvider.getExchangeRate("USD", "KRW")).thenReturn(
            new ExchangeRateProvider.ExchangeRate(new java.math.BigDecimal("1300"), false)
        );

        String idempotencyKey = "idemp";
        Money sellQuantity = Money.of("1", AssetType.CRYPTO, "BTC");
        java.math.BigDecimal sellUnitPrice = new java.math.BigDecimal("50000");

        when(tradeService.executeSellAsset(eq(idempotencyKey), eq(accountId), eq("BTC"), eq(AssetType.CRYPTO),
                eq("USD"), eq(sellQuantity), eq(sellUnitPrice), any(OffsetDateTime.class),
                eq(new java.math.BigDecimal("50000")), eq(false), eq(new java.math.BigDecimal("1300"))))
                .thenReturn(UUID.randomUUID());

        UUID result = facade.sellAsset(idempotencyKey, accountId, "BTC", AssetType.CRYPTO, "USD", sellQuantity, sellUnitPrice);

        org.assertj.core.api.Assertions.assertThat(result).isNotNull();
    }
}
