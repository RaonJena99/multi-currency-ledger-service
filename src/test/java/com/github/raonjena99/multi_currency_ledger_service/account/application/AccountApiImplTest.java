package com.github.raonjena99.multi_currency_ledger_service.account.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.github.raonjena99.multi_currency_ledger_service.account.AccountApi;
import com.github.raonjena99.multi_currency_ledger_service.account.domain.Account;
import com.github.raonjena99.multi_currency_ledger_service.account.domain.MonthlyAccountLedger;
import com.github.raonjena99.multi_currency_ledger_service.account.infrastructure.AccountRepository;
import com.github.raonjena99.multi_currency_ledger_service.account.infrastructure.MonthlyAccountLedgerRepository;
import com.github.raonjena99.multi_currency_ledger_service.common.domain.Money;
import com.github.raonjena99.multi_currency_ledger_service.common.model.AssetType;
import com.github.raonjena99.multi_currency_ledger_service.common.exception.AccountNotFoundException;

@ExtendWith(MockitoExtension.class)
class AccountApiImplTest {

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private MonthlyAccountLedgerRepository monthlyAccountLedgerRepository;

    @InjectMocks
    private AccountApiImpl accountApiImpl;

    @Test
    void getBaseCurrency_should_return_currency() {
        UUID accountId = UUID.randomUUID();
        Account account = org.mockito.Mockito.mock(Account.class);
        when(account.getBaseCurrency()).thenReturn("KRW");
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));

        String result = accountApiImpl.getBaseCurrency(accountId);
        assertThat(result).isEqualTo("KRW");
    }


    @Test
    void getBaseCurrency_should_throw_if_not_found() {
        UUID accountId = UUID.randomUUID();
        when(accountRepository.findById(accountId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> accountApiImpl.getBaseCurrency(accountId))
            .isInstanceOf(AccountNotFoundException.class);
    }

    @Test
    void getBalances_should_return_balances() {
        UUID accountId = UUID.randomUUID();
        MonthlyAccountLedger ledger = org.mockito.Mockito.mock(MonthlyAccountLedger.class);
        when(ledger.getAssetCode()).thenReturn("BTC");
        when(ledger.getBalance()).thenReturn(Money.of("1", AssetType.CRYPTO, "BTC"));
        when(ledger.getAverageUnitPrice()).thenReturn(new BigDecimal("1000"));
        when(ledger.getBaseCurrency()).thenReturn("KRW");

        when(monthlyAccountLedgerRepository.findLatestBalancesByAccountId(accountId)).thenReturn(List.of(ledger));

        List<AccountApi.AccountBalanceDto> balances = accountApiImpl.getBalances(accountId);
        assertThat(balances).hasSize(1);
        assertThat(balances.get(0).assetCode()).isEqualTo("BTC");
        assertThat(balances.get(0).totalQuantity()).isEqualByComparingTo("1");
        assertThat(balances.get(0).avgUnitPrice()).isEqualByComparingTo("1000");
        assertThat(balances.get(0).quoteCurrency()).isEqualTo("KRW");
    }
}
