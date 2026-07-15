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
import com.github.raonjena99.multi_currency_ledger_service.account.infrastructure.AccountRepository;
import com.github.raonjena99.multi_currency_ledger_service.account.infrastructure.MonthlyAccountLedgerRepository;
import com.github.raonjena99.multi_currency_ledger_service.common.model.AssetType;

@ExtendWith(MockitoExtension.class)
class MonthlyLedgerResolverTest {

    @Mock
    private MonthlyAccountLedgerRepository ledgerRepository;

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private MonthlyLedgerInitializer ledgerInitializer;

    @InjectMocks
    private MonthlyLedgerResolver resolver;

    @Test
    @DisplayName("이미 장부가 존재할 경우 바로 반환한다")
    void resolveOrInitializeLedger_alreadyExists() {
        UUID accountId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.parse("2026-07-15T00:00:00Z");
        
        MonthlyAccountLedger ledger = MonthlyAccountLedger.initialize(accountId, "BTC", AssetType.CRYPTO, "2026-07", "KRW");
        when(ledgerRepository.findByAccountIdAndAssetCodeAndLedgerMonth(accountId, "BTC", "2026-07"))
            .thenReturn(Optional.of(ledger));

        MonthlyAccountLedger result = resolver.resolveOrInitializeLedger(accountId, "BTC", AssetType.CRYPTO, now);

        assertThat(result).isEqualTo(ledger);
        verify(ledgerRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("초기화 이후에도 장부 조회가 실패하면 예외를 던진다")
    void resolveOrInitializeLedger_failAfterInit() {
        UUID accountId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.parse("2026-07-15T00:00:00Z");
        
        when(ledgerRepository.findByAccountIdAndAssetCodeAndLedgerMonth(accountId, "BTC", "2026-07"))
            .thenReturn(Optional.empty()); 

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> 
            resolver.resolveOrInitializeLedger(accountId, "BTC", AssetType.CRYPTO, now)
        ).isInstanceOf(IllegalStateException.class);
    }
}
