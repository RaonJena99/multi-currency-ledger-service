package com.github.raonjena99.multi_currency_ledger_service.account.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.github.raonjena99.multi_currency_ledger_service.account.domain.Account;
import com.github.raonjena99.multi_currency_ledger_service.account.domain.MonthlyAccountLedger;
import com.github.raonjena99.multi_currency_ledger_service.account.infrastructure.AccountRepository;
import com.github.raonjena99.multi_currency_ledger_service.account.infrastructure.MonthlyAccountLedgerRepository;
import com.github.raonjena99.multi_currency_ledger_service.common.model.AssetType;

@ExtendWith(MockitoExtension.class)
class MonthlyLedgerInitializerTest {

    @Mock private MonthlyAccountLedgerRepository ledgerRepository;
    @Mock private AccountRepository accountRepository;

    @InjectMocks
    private MonthlyLedgerInitializer initializer;

    @Test
    void initializeInNewTransaction_should_return_early_if_present() {
        UUID accountId = UUID.randomUUID();
        when(ledgerRepository.findByAccountIdAndAssetCodeAndLedgerMonth(accountId, "BTC", "2024-01"))
            .thenReturn(Optional.of(org.mockito.Mockito.mock(MonthlyAccountLedger.class)));

        initializer.initializeInNewTransaction(accountId, "BTC", AssetType.CRYPTO, "2024-01");

        verify(ledgerRepository, never()).findFirstWithLockByAccountIdAndAssetCodeAndLedgerMonthLessThanOrderByLedgerMonthDesc(any(), any(), any());
    }

    @Test
    void initializeInNewTransaction_should_carry_forward_if_prev_exists() {
        UUID accountId = UUID.randomUUID();
        when(ledgerRepository.findByAccountIdAndAssetCodeAndLedgerMonth(accountId, "BTC", "2024-02"))
            .thenReturn(Optional.empty());

        MonthlyAccountLedger prevLedger = MonthlyAccountLedger.initialize(accountId, "BTC", AssetType.CRYPTO, "2024-01", "KRW");
        
        when(ledgerRepository.findFirstWithLockByAccountIdAndAssetCodeAndLedgerMonthLessThanOrderByLedgerMonthDesc(org.mockito.ArgumentMatchers.eq(accountId), org.mockito.ArgumentMatchers.eq("BTC"), org.mockito.ArgumentMatchers.anyString()))
            .thenReturn(Optional.of(prevLedger));

        initializer.initializeInNewTransaction(accountId, "BTC", AssetType.CRYPTO, "2024-02");

        verify(ledgerRepository).save(any(MonthlyAccountLedger.class));
    }

    @Test
    void initializeInNewTransaction_should_initialize_new_if_no_prev() {
        UUID accountId = UUID.randomUUID();
        when(ledgerRepository.findByAccountIdAndAssetCodeAndLedgerMonth(accountId, "BTC", "2024-01"))
            .thenReturn(Optional.empty());

        when(ledgerRepository.findFirstWithLockByAccountIdAndAssetCodeAndLedgerMonthLessThanOrderByLedgerMonthDesc(org.mockito.ArgumentMatchers.eq(accountId), org.mockito.ArgumentMatchers.eq("BTC"), org.mockito.ArgumentMatchers.anyString()))
            .thenReturn(Optional.empty());

        Account account = org.mockito.Mockito.mock(Account.class);
        when(account.getBaseCurrency()).thenReturn("USD");
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));

        initializer.initializeInNewTransaction(accountId, "BTC", AssetType.CRYPTO, "2024-01");

        verify(ledgerRepository).save(any(MonthlyAccountLedger.class));
    }
    
    @Test
    void initializeInNewTransaction_should_throw_if_account_not_found() {
        UUID accountId = UUID.randomUUID();
        when(ledgerRepository.findByAccountIdAndAssetCodeAndLedgerMonth(accountId, "BTC", "2024-01"))
            .thenReturn(Optional.empty());

        when(ledgerRepository.findFirstWithLockByAccountIdAndAssetCodeAndLedgerMonthLessThanOrderByLedgerMonthDesc(org.mockito.ArgumentMatchers.eq(accountId), org.mockito.ArgumentMatchers.eq("BTC"), org.mockito.ArgumentMatchers.anyString()))
            .thenReturn(Optional.empty());

        when(accountRepository.findById(accountId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> initializer.initializeInNewTransaction(accountId, "BTC", AssetType.CRYPTO, "2024-01"))
            .isInstanceOf(com.github.raonjena99.multi_currency_ledger_service.common.exception.AccountNotFoundException.class);
    }
}
