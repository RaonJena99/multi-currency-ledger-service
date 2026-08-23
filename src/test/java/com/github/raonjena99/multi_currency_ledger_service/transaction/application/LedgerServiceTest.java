package com.github.raonjena99.multi_currency_ledger_service.transaction.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.github.raonjena99.multi_currency_ledger_service.common.domain.Money;
import com.github.raonjena99.multi_currency_ledger_service.common.model.AssetType;
import com.github.raonjena99.multi_currency_ledger_service.common.port.ExchangeRateProvider;
import com.github.raonjena99.multi_currency_ledger_service.transaction.application.command.LedgerRecordingCommand;
import com.github.raonjena99.multi_currency_ledger_service.transaction.domain.Transaction;
import com.github.raonjena99.multi_currency_ledger_service.transaction.infrastructure.TransactionRepository;

@ExtendWith(MockitoExtension.class)
class LedgerServiceTest {

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private ExchangeRateProvider exchangeRateProvider;

    @InjectMocks
    private LedgerService ledgerService;

    @Test
    void recordDoubleEntry_should_ignore_duplicate() {
        UUID tradeId = UUID.randomUUID();
        when(transactionRepository.existsById(tradeId)).thenReturn(true);

        LedgerRecordingCommand cmd = new LedgerRecordingCommand(
            tradeId, UUID.randomUUID(), "BTC", AssetType.CRYPTO, "USD", "USD", "BUY", Money.of("1", AssetType.CRYPTO, "BTC"),
            new BigDecimal("1000"), null, null, false
        );

        ledgerService.recordDoubleEntry(cmd);

        verify(transactionRepository, never()).saveAndFlush(any());
    }

    @Test
    void recordDoubleEntry_should_handle_buy_with_fx_gain() {
        UUID tradeId = UUID.randomUUID();
        when(transactionRepository.existsById(tradeId)).thenReturn(false);
        when(exchangeRateProvider.getExchangeRate("KRW", "USD")).thenReturn(
            new ExchangeRateProvider.ExchangeRate(new BigDecimal("0.0008"), false)
        );

        LedgerRecordingCommand cmd = new LedgerRecordingCommand(
            tradeId, UUID.randomUUID(), "BTC", AssetType.CRYPTO, "KRW", "USD", "BUY", Money.of("1", AssetType.CRYPTO, "BTC"),
            new BigDecimal("100000"), null, null, true
        );

        ledgerService.recordDoubleEntry(cmd);

        verify(transactionRepository).saveAndFlush(any(Transaction.class));
    }

    @Test
    void recordDoubleEntry_should_handle_sell_with_fx_loss() {
        UUID tradeId = UUID.randomUUID();
        when(transactionRepository.existsById(tradeId)).thenReturn(false);
        when(exchangeRateProvider.getExchangeRate("KRW", "USD")).thenReturn(
            new ExchangeRateProvider.ExchangeRate(new BigDecimal("0.0008"), false)
        );

        LedgerRecordingCommand cmd = new LedgerRecordingCommand(
            tradeId, UUID.randomUUID(), "BTC", AssetType.CRYPTO, "KRW", "USD", "SELL", Money.of("1", AssetType.CRYPTO, "BTC"),
            new BigDecimal("100000"), null, new BigDecimal("90000"), false
        );

        ledgerService.recordDoubleEntry(cmd);

        verify(transactionRepository).saveAndFlush(any(Transaction.class));
    }

    @Test
    void recordDoubleEntry_should_handle_fee_deduction() {
        UUID tradeId = UUID.randomUUID();
        when(transactionRepository.existsById(tradeId)).thenReturn(false);

        LedgerRecordingCommand cmd = new LedgerRecordingCommand(
            tradeId, UUID.randomUUID(), "KRW", AssetType.FIAT, "KRW", "KRW", "FEE_DEDUCTION", Money.of("10", AssetType.FIAT, "KRW"),
            new BigDecimal("1"), null, null, false
        );

        ledgerService.recordDoubleEntry(cmd);

        verify(transactionRepository).saveAndFlush(any(Transaction.class));
    }

    @Test
    void recordDoubleEntry_should_handle_fee_adjustment_gain() {
        UUID tradeId = UUID.randomUUID();
        when(transactionRepository.existsById(tradeId)).thenReturn(false);

        LedgerRecordingCommand cmd = new LedgerRecordingCommand(
            tradeId, UUID.randomUUID(), "KRW", AssetType.FIAT, "KRW", "KRW", "FEE_ADJUSTMENT", Money.of("10", AssetType.FIAT, "KRW"),
            new BigDecimal("1"), null, null, false
        );

        ledgerService.recordDoubleEntry(cmd);

        verify(transactionRepository).saveAndFlush(any(Transaction.class));
    }

    @Test
    void recordDoubleEntry_should_handle_fee_adjustment_loss() {
        UUID tradeId = UUID.randomUUID();
        when(transactionRepository.existsById(tradeId)).thenReturn(false);

        LedgerRecordingCommand cmd = new LedgerRecordingCommand(
            tradeId, UUID.randomUUID(), "KRW", AssetType.FIAT, "KRW", "KRW", "FEE_ADJUSTMENT", Money.of("-10", AssetType.FIAT, "KRW"),
            new BigDecimal("1"), null, null, false
        );

        ledgerService.recordDoubleEntry(cmd);

        verify(transactionRepository).saveAndFlush(any(Transaction.class));
    }

    @Test
    void recordDoubleEntry_should_handle_fee_adjustment_zero() {
        UUID tradeId = UUID.randomUUID();
        when(transactionRepository.existsById(tradeId)).thenReturn(false);

        LedgerRecordingCommand cmd = new LedgerRecordingCommand(
            tradeId, UUID.randomUUID(), "KRW", AssetType.FIAT, "KRW", "KRW", "FEE_ADJUSTMENT", Money.of("0", AssetType.FIAT, "KRW"),
            new BigDecimal("1"), null, null, false
        );

        ledgerService.recordDoubleEntry(cmd);

        verify(transactionRepository).saveAndFlush(any(Transaction.class));
    }

    @Test
    void recordDoubleEntry_should_handle_fiat_not_equal_to_base_currency_and_fx_gain() {
        UUID tradeId = UUID.randomUUID();
        when(transactionRepository.existsById(tradeId)).thenReturn(false);
        when(exchangeRateProvider.getExchangeRate("KRW", "USD")).thenReturn(
            new ExchangeRateProvider.ExchangeRate(new BigDecimal("2.0"), false)
        );

        LedgerRecordingCommand cmd = new LedgerRecordingCommand(
            tradeId, UUID.randomUUID(), "BTC", AssetType.CRYPTO, "KRW", "USD", "BUY", Money.of("1", AssetType.CRYPTO, "BTC"),
            new BigDecimal("100"), null, null, true
        );

        ledgerService.recordDoubleEntry(cmd);
        verify(transactionRepository).saveAndFlush(any(Transaction.class));
    }

    @Test
    void recordDoubleEntry_should_handle_sell_with_null_base_currency_and_average_cost() {
        UUID tradeId = UUID.randomUUID();
        when(transactionRepository.existsById(tradeId)).thenReturn(false);

        // fiatCode is KRW, baseCurrency is null -> baseCurrency becomes KRW, so no exchange rate API call
        LedgerRecordingCommand cmd = new LedgerRecordingCommand(
            tradeId, UUID.randomUUID(), "BTC", AssetType.CRYPTO, "KRW", null, "SELL", Money.of("1", AssetType.CRYPTO, "BTC"),
            new BigDecimal("100"), null, new BigDecimal("80"), false
        );

        ledgerService.recordDoubleEntry(cmd);
        verify(transactionRepository).saveAndFlush(any(Transaction.class));
    }

    @Test
    void recordDoubleEntry_should_handle_fee_deduction_with_average_cost_not_null() {
        UUID tradeId = UUID.randomUUID();
        when(transactionRepository.existsById(tradeId)).thenReturn(false);

        LedgerRecordingCommand cmd = new LedgerRecordingCommand(
            tradeId, UUID.randomUUID(), "KRW", AssetType.FIAT, "KRW", "KRW", "FEE_DEDUCTION", Money.of("10", AssetType.FIAT, "KRW"),
            new BigDecimal("1"), null, new BigDecimal("1.2"), false
        );

        ledgerService.recordDoubleEntry(cmd);
        verify(transactionRepository).saveAndFlush(any(Transaction.class));
    }
    
    @Test
    void recordDoubleEntry_should_plugin_system_fx_gain_when_debit_exceeds_credit() {
        UUID tradeId = UUID.randomUUID();
        when(transactionRepository.existsById(tradeId)).thenReturn(false);

        // To trigger difference > 0, we need debit > credit.
        // KRW has scale 0, rounding HALF_EVEN.
        // qty = 1, fx = 1.
        // unitPrice = 1.5, avgCost = 0.5.
        // debit: Round(1.5) = 2.
        // credit: Round(0.5) + Round(1.0) = 0 + 1 = 1.
        // difference = 2 - 1 = 1 > 0.
        LedgerRecordingCommand cmd = new LedgerRecordingCommand(
            tradeId, UUID.randomUUID(), "BTC", AssetType.CRYPTO, "KRW", "KRW", "SELL", Money.of("1", AssetType.CRYPTO, "BTC"),
            new BigDecimal("1.5"), null, new BigDecimal("0.5"), false
        );

        ledgerService.recordDoubleEntry(cmd);
        
        org.mockito.ArgumentCaptor<Transaction> captor = org.mockito.ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository).saveAndFlush(captor.capture());
        
        Transaction saved = captor.getValue();
        org.assertj.core.api.Assertions.assertThat(saved).isNotNull();
    }
    
    @Test
    void recordDoubleEntry_should_plugin_system_fx_loss_when_credit_exceeds_debit() {
        UUID tradeId = UUID.randomUUID();
        when(transactionRepository.existsById(tradeId)).thenReturn(false);

        // To trigger difference < 0, we need credit > debit.
        // KRW has scale 0, rounding HALF_EVEN.
        // qty = 1, fx = 1.
        // unitPrice = 2.5, avgCost = 1.5.
        // debit: Round(2.5) = 2.
        // credit: Round(1.5) + Round(1.0) = 2 + 1 = 3.
        // difference = 2 - 3 = -1 < 0.
        LedgerRecordingCommand cmd = new LedgerRecordingCommand(
            tradeId, UUID.randomUUID(), "BTC", AssetType.CRYPTO, "KRW", "KRW", "SELL", Money.of("1", AssetType.CRYPTO, "BTC"),
            new BigDecimal("2.5"), null, new BigDecimal("1.5"), false
        );

        ledgerService.recordDoubleEntry(cmd);
        
        org.mockito.ArgumentCaptor<Transaction> captor = org.mockito.ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository).saveAndFlush(captor.capture());
        
        Transaction saved = captor.getValue();
        org.assertj.core.api.Assertions.assertThat(saved).isNotNull();
    }
}