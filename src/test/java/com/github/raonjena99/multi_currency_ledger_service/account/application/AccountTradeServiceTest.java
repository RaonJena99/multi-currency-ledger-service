package com.github.raonjena99.multi_currency_ledger_service.account.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;

import com.github.raonjena99.multi_currency_ledger_service.account.domain.IdempotencyRecord;
import com.github.raonjena99.multi_currency_ledger_service.account.domain.MonthlyAccountLedger;
import com.github.raonjena99.multi_currency_ledger_service.account.domain.event.TradeExecutedEvent;
import com.github.raonjena99.multi_currency_ledger_service.account.infrastructure.IdempotencyRecordRepository;
import com.github.raonjena99.multi_currency_ledger_service.account.infrastructure.MonthlyAccountLedgerRepository;
import com.github.raonjena99.multi_currency_ledger_service.common.domain.Money;
import com.github.raonjena99.multi_currency_ledger_service.common.exception.DuplicateTradeRequestException;
import com.github.raonjena99.multi_currency_ledger_service.common.model.AssetType;

@ExtendWith(MockitoExtension.class)
class AccountTradeServiceTest {

    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private IdempotencyRecordRepository idempotencyRepository;
    @Mock private MonthlyAccountLedgerRepository monthlyAccountLedgerRepository;

    @InjectMocks
    private AccountTradeService tradeService;

    @Test
    void executeBuyAsset_should_throw_on_duplicate_request() {
        when(idempotencyRepository.saveAndFlush(any(IdempotencyRecord.class)))
            .thenThrow(new DataIntegrityViolationException("Duplicate"));

        assertThatThrownBy(() -> 
            tradeService.executeBuyAsset("idemp-key", UUID.randomUUID(), "BTC", AssetType.CRYPTO, "KRW", 
                                         Money.of("1", AssetType.CRYPTO, "BTC"), BigDecimal.ONE, OffsetDateTime.now(), 
                                         BigDecimal.ONE, false, null)
        ).isInstanceOf(DuplicateTradeRequestException.class);
    }

    @Test
    void executeBuyAsset_should_process_successfully_with_fiat_to_base_rate() {
        UUID accountId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        
        MonthlyAccountLedger targetLedger = org.mockito.Mockito.mock(MonthlyAccountLedger.class);
        MonthlyAccountLedger fiatLedger = org.mockito.Mockito.mock(MonthlyAccountLedger.class);
        
        when(targetLedger.getBaseCurrency()).thenReturn("USD");
        when(monthlyAccountLedgerRepository.findByAccountIdAndAssetCodeAndLedgerMonth(eq(accountId), eq("BTC"), anyString()))
            .thenReturn(Optional.of(targetLedger));
        when(monthlyAccountLedgerRepository.findByAccountIdAndAssetCodeAndLedgerMonth(eq(accountId), eq("KRW"), anyString()))
            .thenReturn(Optional.of(fiatLedger));

        UUID tradeId = tradeService.executeBuyAsset(
            "idemp-key", accountId, "BTC", AssetType.CRYPTO, "KRW", 
            Money.of("1", AssetType.CRYPTO, "BTC"), new BigDecimal("10000"), now, 
            new BigDecimal("10000"), false, new BigDecimal("0.00075")
        );

        assertThat(tradeId).isNotNull();
        verify(fiatLedger).subtractBalance(any());
        verify(targetLedger).addBalance(any(), eq(new BigDecimal("7.50000"))); // 10000 * 0.00075
        verify(monthlyAccountLedgerRepository).save(targetLedger);
        verify(monthlyAccountLedgerRepository).save(fiatLedger);
        verify(eventPublisher).publishEvent(any(TradeExecutedEvent.class));
    }

    @Test
    void executeSellAsset_should_throw_on_duplicate_request() {
        when(idempotencyRepository.saveAndFlush(any(IdempotencyRecord.class)))
            .thenThrow(new DataIntegrityViolationException("Duplicate"));

        assertThatThrownBy(() -> 
            tradeService.executeSellAsset("idemp-key", UUID.randomUUID(), "BTC", AssetType.CRYPTO, "KRW", 
                                         Money.of("1", AssetType.CRYPTO, "BTC"), BigDecimal.ONE, OffsetDateTime.now(), 
                                         BigDecimal.ONE, false, null)
        ).isInstanceOf(DuplicateTradeRequestException.class);
    }

    @Test
    void executeSellAsset_should_process_successfully_without_fiat_to_base_rate() {
        UUID accountId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        
        MonthlyAccountLedger targetLedger = org.mockito.Mockito.mock(MonthlyAccountLedger.class);
        MonthlyAccountLedger fiatLedger = org.mockito.Mockito.mock(MonthlyAccountLedger.class);
        
        when(fiatLedger.getBaseCurrency()).thenReturn("KRW");
        when(targetLedger.getBaseCurrency()).thenReturn("KRW");
        
        when(monthlyAccountLedgerRepository.findByAccountIdAndAssetCodeAndLedgerMonth(eq(accountId), eq("BTC"), anyString()))
            .thenReturn(Optional.of(targetLedger));
        when(monthlyAccountLedgerRepository.findByAccountIdAndAssetCodeAndLedgerMonth(eq(accountId), eq("KRW"), anyString()))
            .thenReturn(Optional.of(fiatLedger));
            
        when(targetLedger.subtractBalance(any())).thenReturn(BigDecimal.ZERO);

        UUID tradeId = tradeService.executeSellAsset(
            "idemp-key", accountId, "BTC", AssetType.CRYPTO, "KRW", 
            Money.of("1", AssetType.CRYPTO, "BTC"), new BigDecimal("10000"), now, 
            new BigDecimal("10000"), false, null
        );

        assertThat(tradeId).isNotNull();
        verify(targetLedger).subtractBalance(any());
        verify(fiatLedger).addBalance(any(), eq(BigDecimal.ONE)); // Default 1 when null fiatToBaseRate
        verify(monthlyAccountLedgerRepository).save(targetLedger);
        verify(monthlyAccountLedgerRepository).save(fiatLedger);
        verify(eventPublisher).publishEvent(any(TradeExecutedEvent.class));
    }
    
    @Test
    void executeSellAsset_should_use_provided_fiat_to_base_rate() {
        OffsetDateTime now = OffsetDateTime.now();
        UUID accountId = UUID.randomUUID();

        MonthlyAccountLedger fiatLedger = org.mockito.Mockito.mock(MonthlyAccountLedger.class);
        when(fiatLedger.getBaseCurrency()).thenReturn("KRW");
        
        MonthlyAccountLedger targetLedger = org.mockito.Mockito.mock(MonthlyAccountLedger.class);
        when(targetLedger.getBaseCurrency()).thenReturn("KRW");

        when(monthlyAccountLedgerRepository.findByAccountIdAndAssetCodeAndLedgerMonth(org.mockito.ArgumentMatchers.eq(accountId), org.mockito.ArgumentMatchers.eq("USD"), org.mockito.ArgumentMatchers.anyString()))
            .thenReturn(Optional.of(fiatLedger));
        when(monthlyAccountLedgerRepository.findByAccountIdAndAssetCodeAndLedgerMonth(org.mockito.ArgumentMatchers.eq(accountId), org.mockito.ArgumentMatchers.eq("BTC"), org.mockito.ArgumentMatchers.anyString()))
            .thenReturn(Optional.of(targetLedger));

        when(targetLedger.subtractBalance(any())).thenReturn(BigDecimal.ZERO);
        when(monthlyAccountLedgerRepository.save(any())).thenReturn(fiatLedger);

        UUID tradeId = tradeService.executeSellAsset(
            "idemp-key-fx", accountId, "BTC", AssetType.CRYPTO, "USD", 
            Money.of("1", AssetType.CRYPTO, "BTC"), new BigDecimal("100"), now, 
            new BigDecimal("100"), false, new BigDecimal("1300")
        );

        assertThat(tradeId).isNotNull();
        // Since fiatToBaseRate is 1300, addBalance should be called with 1300
        verify(fiatLedger).addBalance(any(), eq(new BigDecimal("1300")));
    }
}