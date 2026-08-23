package com.github.raonjena99.multi_currency_ledger_service.transaction.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.github.raonjena99.multi_currency_ledger_service.common.domain.Money;
import com.github.raonjena99.multi_currency_ledger_service.common.exception.DoubleEntryImbalanceException;
import com.github.raonjena99.multi_currency_ledger_service.common.model.AssetType;

class TransactionTest {

    @Test
    void record_should_create_valid_transaction() {
        UUID id = UUID.randomUUID();
        Transaction transaction = Transaction.record(id, "DEPOSIT", "Deposit TEST");
        
        assertThat(transaction.getId()).isEqualTo(id);
        assertThat(transaction.getTransactionType()).isEqualTo("DEPOSIT");
        assertThat(transaction.getDescription()).isEqualTo("Deposit TEST");
        assertThat(transaction.getTransactedAt()).isNotNull();
        assertThat(transaction.isNew()).isTrue();
    }

    @Test
    void verifyDoubleEntry_should_pass_for_balanced_entries() {
        Transaction transaction = Transaction.record(UUID.randomUUID(), "TRADE", "Trade TEST");
        UUID accountId = UUID.randomUUID();
        
        // 차변: BTC 매수 (1 BTC, 단가 10000)
        transaction.addBuyEntry(accountId, "BTC", Money.of(BigDecimal.ONE, AssetType.CRYPTO, "BTC"), BigDecimal.valueOf(10000), BigDecimal.ONE, "KRW");
        
        // 대변: KRW 차감 (10000 KRW, 평균단가 1)
        transaction.addSellEntry(accountId, "KRW", Money.of(BigDecimal.valueOf(10000), AssetType.FIAT, "KRW"), BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, "KRW");
        
        ReflectionTestUtils.invokeMethod(transaction, "verifyDoubleEntry"); // Should not throw
    }

    @Test
    void verifyDoubleEntry_should_fail_for_imbalanced_entries() {
        Transaction transaction = Transaction.record(UUID.randomUUID(), "TRADE", "Trade TEST");
        UUID accountId = UUID.randomUUID();
        
        // 차변: BTC 매수 (1 BTC, 단가 10000)
        transaction.addBuyEntry(accountId, "BTC", Money.of(BigDecimal.ONE, AssetType.CRYPTO, "BTC"), BigDecimal.valueOf(10000), BigDecimal.ONE, "KRW");
        
        // 대변: KRW 차감 (9000 KRW, 평균단가 1) - 불일치
        transaction.addSellEntry(accountId, "KRW", Money.of(BigDecimal.valueOf(9000), AssetType.FIAT, "KRW"), BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, "KRW");
        
        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(transaction, "verifyDoubleEntry"))
                .isInstanceOf(DoubleEntryImbalanceException.class)
                .hasMessageContaining("Double-entry accounting error for currency [KRW]");
    }

    @Test
    void verifyDoubleEntry_should_fail_when_credit_exists_without_debit() {
        Transaction transaction = Transaction.record(UUID.randomUUID(), "TRADE", "Trade TEST");
        UUID accountId = UUID.randomUUID();
        
        // 대변만 존재
        transaction.addSellEntry(accountId, "KRW", Money.of(BigDecimal.valueOf(10000), AssetType.FIAT, "KRW"), BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, "KRW");
        
        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(transaction, "verifyDoubleEntry"))
                .isInstanceOf(DoubleEntryImbalanceException.class)
                .hasMessageContaining("Credit exists without Debit");
    }

    @Test
    void markNotNew_should_set_isNew_to_false() {
        Transaction transaction = Transaction.record(UUID.randomUUID(), "DEPOSIT", "Deposit TEST");
        assertThat(transaction.isNew()).isTrue();
        
        ReflectionTestUtils.invokeMethod(transaction, "markNotNew");
        assertThat(transaction.isNew()).isFalse();
    }

    @Test
    void onPersist_should_call_verifyDoubleEntry() {
        Transaction transaction = Transaction.record(UUID.randomUUID(), "TRADE", "Trade TEST");
        UUID accountId = UUID.randomUUID();
        
        transaction.addBuyEntry(accountId, "BTC", Money.of(BigDecimal.ONE, AssetType.CRYPTO, "BTC"), BigDecimal.valueOf(10000), BigDecimal.ONE, "KRW");
        transaction.addSellEntry(accountId, "KRW", Money.of(BigDecimal.valueOf(10000), AssetType.FIAT, "KRW"), BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, "KRW");
        
        // This will indirectly call verifyDoubleEntry, should pass without throwing
        ReflectionTestUtils.invokeMethod(transaction, "onPersist");
    }

    @Test
    void verifyDoubleEntry_should_pass_with_pnl() {
        Transaction transaction = Transaction.record(UUID.randomUUID(), "TRADE", "Trade TEST");
        UUID accountId = UUID.randomUUID();
        
        // BTC 1개를 10000 KRW에 매도, 평단가는 8000 KRW
        // 차변: KRW 증가 (10000 KRW)
        transaction.addBuyEntry(accountId, "KRW", Money.of(BigDecimal.valueOf(10000), AssetType.FIAT, "KRW"), BigDecimal.ONE, BigDecimal.ONE, "KRW");
        
        // 대변: BTC 매도 (1 BTC), 단가 10000, 평단 8000 -> PNL 2000 KRW
        // sellQuantity=1, sellPrice=10000, averageCost=8000
        // costPrice = 8000, PNL = (10000 - 8000) * 1 = 2000 KRW
        // 대변 총 금액 = costPrice * quantity = 8000
        // verifyDoubleEntry에서 대변에 PNL(2000)을 더해서 10000 = 10000 일치 확인
        transaction.addSellEntry(accountId, "BTC", Money.of(BigDecimal.ONE, AssetType.CRYPTO, "BTC"), BigDecimal.valueOf(10000), BigDecimal.ONE, BigDecimal.valueOf(8000), "KRW");
        
        ReflectionTestUtils.invokeMethod(transaction, "verifyDoubleEntry"); // Should pass
    }
}