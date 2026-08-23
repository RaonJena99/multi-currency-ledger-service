package com.github.raonjena99.multi_currency_ledger_service.transaction.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.github.raonjena99.multi_currency_ledger_service.common.domain.Money;
import com.github.raonjena99.multi_currency_ledger_service.common.model.AssetType;
import com.github.raonjena99.multi_currency_ledger_service.common.model.EntryType;

class TransactionEntryTest {

    @Test
    void createBuyEntry_should_set_exchange_rate_to_one_if_null() {
        Transaction transaction = Transaction.record(UUID.randomUUID(), "TRADE", "Test");
        
        TransactionEntry entry = TransactionEntry.createBuyEntry(
            transaction, UUID.randomUUID(), "BTC", 
            Money.of("1", AssetType.CRYPTO, "BTC"), 
            new BigDecimal("10000"), null, "KRW"
        );
        
        assertThat(entry.getExchangeRate()).isEqualByComparingTo("1");
        assertThat(entry.getAmount().getAmount()).isEqualByComparingTo("10000");
    }

    @Test
    void createSellEntry_should_handle_null_average_cost() {
        Transaction transaction = Transaction.record(UUID.randomUUID(), "TRADE", "Test");
        
        TransactionEntry entry = TransactionEntry.createSellEntry(
            transaction, UUID.randomUUID(), "BTC", 
            Money.of("1", AssetType.CRYPTO, "BTC"), 
            new BigDecimal("10000"), new BigDecimal("1"), null, "KRW"
        );
        
        // PNL should be zero
        assertThat(entry.getRealizedPnl().getAmount()).isEqualByComparingTo("0");
        assertThat(entry.getUnitPrice()).isEqualByComparingTo("10000");
        assertThat(entry.getAmount().getAmount()).isEqualByComparingTo("10000");
    }

    @Test
    void should_return_correct_fields() {
        Transaction transaction = Transaction.record(UUID.randomUUID(), "TRADE", "Test");
        UUID accountId = UUID.randomUUID();
        TransactionEntry entry = TransactionEntry.createBuyEntry(
            transaction, accountId, "BTC", 
            Money.of("1", AssetType.CRYPTO, "BTC"), 
            new BigDecimal("10000"), new BigDecimal("2"), "KRW"
        );
        
        assertThat(entry.getId()).isNull();
        assertThat(entry.getTransaction()).isEqualTo(transaction);
        assertThat(entry.getAccountId()).isEqualTo(accountId);
        assertThat(entry.getEntryType()).isEqualTo(EntryType.DEBIT);
        assertThat(entry.getAssetCode()).isEqualTo("BTC");
        assertThat(entry.getQuantity().getAmount()).isEqualByComparingTo("1");
        assertThat(entry.getAmount().getAmount()).isEqualByComparingTo("20000"); // 10000 * 2
    }
}
