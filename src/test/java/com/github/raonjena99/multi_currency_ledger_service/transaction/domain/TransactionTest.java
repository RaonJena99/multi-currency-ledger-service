package com.github.raonjena99.multi_currency_ledger_service.transaction.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.github.raonjena99.multi_currency_ledger_service.common.domain.Money;
import com.github.raonjena99.multi_currency_ledger_service.common.model.AssetType;

@DisplayName("도메인 단위 테스트: Transaction (복식부기 대차평균 정합성 검증)")
class TransactionTest {

    @Test
    @DisplayName("차변과 대변의 기준 화폐(Fiat) 환산 가치가 완벽히 일치하면 onPersist 검증을 통과한다.")
    void onPersist_success_when_balanced() {
        // given
        Transaction transaction = new Transaction(UUID.randomUUID(), "BUY", "Buy 2 BTC");
        UUID accountId = UUID.randomUUID();

        // 차변: BTC 2개 유입 (단가 50,000 -> 총 가치 100,000)
        transaction.addBuyEntry(accountId, "BTC", Money.of("2", AssetType.CRYPTO), 
                                Money.of("50000", AssetType.FIAT), BigDecimal.ONE);
        
        // 대변: USD 100,000 유출 (총 가치 100,000)
        transaction.addSellEntry(accountId, "USD", Money.of("100000", AssetType.FIAT), 
                                Money.of("1", AssetType.FIAT), BigDecimal.ONE, Money.of("1", AssetType.FIAT));

        // when & then (예외가 발생하지 않으면 성공)
        transaction.onPersist(); 
        assertThat(transaction.getEntries()).hasSize(2);
    }

    @Test
    @DisplayName("차변과 대변의 Fiat 환산 가치가 단 1이라도 불일치하면 IllegalStateException 예외가 발생한다.")
    void onPersist_fails_when_unbalanced() {
        // given
        Transaction transaction = new Transaction(UUID.randomUUID(), "BUY", "Unbalanced Trade");
        UUID accountId = UUID.randomUUID();

        // 차변: 총 가치 100,000
        transaction.addBuyEntry(accountId, "BTC", Money.of("2", AssetType.CRYPTO), 
                                Money.of("50000", AssetType.FIAT), BigDecimal.ONE);
        
        // 대변: 총 가치 90,000 (10,000 부족)
        transaction.addSellEntry(accountId, "USD", Money.of("90000", AssetType.FIAT), 
                                Money.of("1", AssetType.FIAT), BigDecimal.ONE, Money.of("1", AssetType.FIAT));

        // when & then
        assertThatThrownBy(transaction::onPersist)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Double-entry accounting error");
    }

    @Test
    @DisplayName("isNew - 항상 true 반환")
    void isNew_alwaysTrue() {
        Transaction transaction = new Transaction(UUID.randomUUID(), "TEST", "Desc");
        assertThat(transaction.isNew()).isTrue();
    }

    @Test
    @DisplayName("exchangeRate가 null이면 ONE으로 대체되고, realizedPnl이 null이어도 검증을 통과한다.")
    void null_exchangeRate_and_pnl() throws Exception {
        Transaction transaction = new Transaction(UUID.randomUUID(), "BUY", "Desc");
        UUID accountId = UUID.randomUUID();

        // null exchange rate -> fallback to ONE
        transaction.addBuyEntry(accountId, "BTC", Money.of("2", AssetType.CRYPTO), 
                                Money.of("50000", AssetType.FIAT), null);
        
        transaction.addSellEntry(accountId, "USD", Money.of("100000", AssetType.FIAT), 
                                Money.of("1", AssetType.FIAT), BigDecimal.ONE, Money.of("1", AssetType.FIAT));

        // Use reflection to set realizedPnl to null to cover the null check branch
        TransactionEntry sellEntry = transaction.getEntries().get(1);
        java.lang.reflect.Field field = TransactionEntry.class.getDeclaredField("realizedPnl");
        field.setAccessible(true);
        field.set(sellEntry, null);

        // Should balance since 2 * 50000 * 1 = 100000 * 1 * 1, and pnl is null so ignored
        transaction.onPersist();
        assertThat(transaction.getEntries().get(0).getExchangeRate()).isEqualByComparingTo(BigDecimal.ONE);
    }

    @Test
    @DisplayName("빈 트랜잭션이라도 onPersist 검증(0=0)을 통과한다.")
    void onPersist_emptyEntries() {
        Transaction tx = new Transaction(UUID.randomUUID(), "TEST", "Empty");
        tx.onPersist(); // Should pass, 0 = 0
    }
}