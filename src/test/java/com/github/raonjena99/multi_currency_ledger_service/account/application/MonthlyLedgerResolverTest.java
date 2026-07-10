package com.github.raonjena99.multi_currency_ledger_service.account.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.github.raonjena99.multi_currency_ledger_service.account.domain.MonthlyAccountLedger;
import com.github.raonjena99.multi_currency_ledger_service.account.infrastructure.MonthlyAccountLedgerRepository;
import com.github.raonjena99.multi_currency_ledger_service.common.model.AssetType;

@ExtendWith(MockitoExtension.class)
class MonthlyLedgerResolverTest {

    @Mock
    private MonthlyAccountLedgerRepository ledgerRepository;

    @InjectMocks
    private MonthlyLedgerResolver resolver;

    @Test
    @DisplayName("이미 장부가 존재할 경우 바로 반환한다")
    void resolveOrInitializeLedger_alreadyExists() {
        UUID accountId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.parse("2026-07-15T00:00:00Z");
        
        MonthlyAccountLedger ledger = new MonthlyAccountLedger(accountId, "BTC", AssetType.CRYPTO, "2026-07");
        when(ledgerRepository.findByAccountIdAndAssetCodeAndLedgerMonth(accountId, "BTC", "2026-07"))
            .thenReturn(Optional.of(ledger));

        MonthlyAccountLedger result = resolver.resolveOrInitializeLedger(accountId, "BTC", AssetType.CRYPTO, now);

        assertThat(result).isEqualTo(ledger);
        verify(ledgerRepository, never()).save(any());
    }

    @Test
    @DisplayName("초기화 시 이미 장부가 존재할 경우 로직을 무시하고 리턴한다")
    void initializeInNewTransaction_alreadyExists() {
        UUID accountId = UUID.randomUUID();
        MonthlyAccountLedger ledger = new MonthlyAccountLedger(accountId, "BTC", AssetType.CRYPTO, "2026-07");
        
        when(ledgerRepository.findByAccountIdAndAssetCodeAndLedgerMonth(accountId, "BTC", "2026-07"))
            .thenReturn(Optional.of(ledger));

        resolver.initializeInNewTransaction(accountId, "BTC", AssetType.CRYPTO, "2026-07");

        verify(ledgerRepository, never()).save(any());
    }

    @Test
    @DisplayName("초기화 시 유니크 제약 조건 위반 발생 시 조용히 무시한다")
    void initializeInNewTransaction_dataIntegrityViolation() {
        UUID accountId = UUID.randomUUID();
        when(ledgerRepository.findByAccountIdAndAssetCodeAndLedgerMonth(accountId, "BTC", "2026-07"))
            .thenReturn(Optional.empty());
        when(ledgerRepository.findFirstByAccountIdAndAssetCodeOrderByLedgerMonthDesc(accountId, "BTC"))
            .thenReturn(Optional.empty());
        when(ledgerRepository.save(any())).thenThrow(new org.springframework.dao.DataIntegrityViolationException("duplicate"));

        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> 
            resolver.initializeInNewTransaction(accountId, "BTC", AssetType.CRYPTO, "2026-07")
        );
    }

    @Test
    @DisplayName("이전 장부가 있을 경우 이월하여 초기화한다")
    void initializeInNewTransaction_withPrevLedger() {
        UUID accountId = UUID.randomUUID();
        when(ledgerRepository.findByAccountIdAndAssetCodeAndLedgerMonth(accountId, "BTC", "2026-07"))
            .thenReturn(Optional.empty());
        
        MonthlyAccountLedger prev = new MonthlyAccountLedger(accountId, "BTC", AssetType.CRYPTO, "2026-06");
        when(ledgerRepository.findFirstByAccountIdAndAssetCodeOrderByLedgerMonthDesc(accountId, "BTC"))
            .thenReturn(Optional.of(prev));

        resolver.initializeInNewTransaction(accountId, "BTC", AssetType.CRYPTO, "2026-07");
        verify(ledgerRepository).save(any());
    }
}
