package com.github.raonjena99.multi_currency_ledger_service.transaction.domain;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.github.raonjena99.multi_currency_ledger_service.common.model.AssetType;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

public class TransactionTest {
    @Test
    @DisplayName("차변과 대변의 합이 정확히 일치하면 검증을 통과한다")
    void verifyDoubleEntry_success() {
        Transaction tx = new Transaction(UUID.randomUUID(), "TRADE", "Valid Trade");
        UUID accountId = UUID.randomUUID();

        // 차변(DEBIT): BTC 1개 매수 @ 50,000$ (Amount: 50,000)
        tx.addBuyEntry(accountId, "BTC", AssetType.CRYPTO, BigDecimal.ONE, BigDecimal.valueOf(50000), BigDecimal.ONE);
        
        // 대변(CREDIT): USD 50,000 매도 (Amount: 50,000)
        tx.addSellEntry(accountId, "USD", AssetType.FIAT, BigDecimal.valueOf(50000), BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE);

        assertThatCode(tx::verifyDoubleEntry).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("차변과 대변의 합이 불일치하면 IllegalStateException 예외가 발생한다")
    void verifyDoubleEntry_fails_when_unbalanced() {
        Transaction tx = new Transaction(UUID.randomUUID(), "TRADE", "Invalid Trade");
        UUID accountId = UUID.randomUUID();

        // 차변: 50,000$
        tx.addBuyEntry(accountId, "BTC", AssetType.CRYPTO, BigDecimal.ONE, BigDecimal.valueOf(50000), BigDecimal.ONE);
        
        // 대변: 49,000$ (대차 불일치)
        tx.addSellEntry(accountId, "USD", AssetType.FIAT, BigDecimal.valueOf(49000), BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE);

        assertThatThrownBy(tx::verifyDoubleEntry)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Debits and Credits must balance");
    }
}
