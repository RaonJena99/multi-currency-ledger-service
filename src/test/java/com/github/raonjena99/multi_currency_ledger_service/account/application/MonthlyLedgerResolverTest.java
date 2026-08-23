package com.github.raonjena99.multi_currency_ledger_service.account.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import com.github.raonjena99.multi_currency_ledger_service.account.domain.MonthlyAccountLedger;
import com.github.raonjena99.multi_currency_ledger_service.account.infrastructure.MonthlyAccountLedgerRepository;
import com.github.raonjena99.multi_currency_ledger_service.common.model.AssetType;

@ExtendWith(MockitoExtension.class)
class MonthlyLedgerResolverTest {

    @Mock private MonthlyAccountLedgerRepository ledgerRepository;
    @Mock private MonthlyLedgerInitializer ledgerInitializer;

    @InjectMocks
    private MonthlyLedgerResolver resolver;

    @Test
    void resolveOrInitializeLedger_should_return_immediately_if_present() {
        UUID accountId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        String targetMonth = now.format(DateTimeFormatter.ofPattern("yyyy-MM"));
        
        MonthlyAccountLedger ledger = org.mockito.Mockito.mock(MonthlyAccountLedger.class);
        when(ledgerRepository.findByAccountIdAndAssetCodeAndLedgerMonth(accountId, "BTC", targetMonth))
            .thenReturn(Optional.of(ledger));

        MonthlyAccountLedger result = resolver.resolveOrInitializeLedger(accountId, "BTC", AssetType.CRYPTO, now);

        assertThat(result).isEqualTo(ledger);
    }

    @Test
    void resolveOrInitializeLedger_should_initialize_and_return_if_absent() {
        UUID accountId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        String targetMonth = now.format(DateTimeFormatter.ofPattern("yyyy-MM"));
        
        MonthlyAccountLedger ledger = org.mockito.Mockito.mock(MonthlyAccountLedger.class);
        
        when(ledgerRepository.findByAccountIdAndAssetCodeAndLedgerMonth(accountId, "BTC", targetMonth))
            .thenReturn(Optional.empty()) // First check
            .thenReturn(Optional.of(ledger)); // Second check after initialization

        MonthlyAccountLedger result = resolver.resolveOrInitializeLedger(accountId, "BTC", AssetType.CRYPTO, now);

        assertThat(result).isEqualTo(ledger);
        verify(ledgerInitializer).initializeInNewTransaction(accountId, "BTC", AssetType.CRYPTO, targetMonth);
    }

    @Test
    void resolveOrInitializeLedger_should_handle_concurrent_initialization() {
        UUID accountId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        String targetMonth = now.format(DateTimeFormatter.ofPattern("yyyy-MM"));
        
        MonthlyAccountLedger ledger = org.mockito.Mockito.mock(MonthlyAccountLedger.class);
        
        when(ledgerRepository.findByAccountIdAndAssetCodeAndLedgerMonth(accountId, "BTC", targetMonth))
            .thenReturn(Optional.empty()) // First check
            .thenReturn(Optional.of(ledger)); // Second check after initialization
            
        doThrow(new DataIntegrityViolationException("Duplicate"))
            .when(ledgerInitializer).initializeInNewTransaction(accountId, "BTC", AssetType.CRYPTO, targetMonth);

        MonthlyAccountLedger result = resolver.resolveOrInitializeLedger(accountId, "BTC", AssetType.CRYPTO, now);

        assertThat(result).isEqualTo(ledger);
        verify(ledgerInitializer).initializeInNewTransaction(accountId, "BTC", AssetType.CRYPTO, targetMonth);
    }

    @Test
    void resolveOrInitializeLedger_should_throw_if_still_absent_after_initialization() {
        UUID accountId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        String targetMonth = now.format(DateTimeFormatter.ofPattern("yyyy-MM"));
        
        when(ledgerRepository.findByAccountIdAndAssetCodeAndLedgerMonth(accountId, "BTC", targetMonth))
            .thenReturn(Optional.empty()) // First check
            .thenReturn(Optional.empty()); // Second check after initialization

        assertThatThrownBy(() -> resolver.resolveOrInitializeLedger(accountId, "BTC", AssetType.CRYPTO, now))
            .isInstanceOf(IllegalStateException.class);
    }
}
