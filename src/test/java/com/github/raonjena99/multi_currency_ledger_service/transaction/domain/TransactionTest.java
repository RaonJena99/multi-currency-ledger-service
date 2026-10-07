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
import com.github.raonjena99.multi_currency_ledger_service.common.model.EntryType;

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

        // 대변: BTC 매도 (1 BTC), 단가 10000, 평단 8000 -> 원가 8000 + 실현 이익 2000
        // 실현 이익은 고객 계정의 별도 대변 분개(REALIZED_PNL_TRADING)로 함께 만들어지므로 차변 10000 = 대변 8000 + 2000
        transaction.addSellEntry(accountId, "BTC", Money.of(BigDecimal.ONE, AssetType.CRYPTO, "BTC"), BigDecimal.valueOf(10000), BigDecimal.ONE, BigDecimal.valueOf(8000), "KRW");

        ReflectionTestUtils.invokeMethod(transaction, "verifyDoubleEntry"); // Should pass
    }

    private static Money krw(long amount) {
        return Money.of(BigDecimal.valueOf(amount), AssetType.FIAT, "KRW");
    }

    private static Money btc(long amount) {
        return Money.of(BigDecimal.valueOf(amount), AssetType.CRYPTO, "BTC");
    }

    @Test
    void addSellEntry_with_gain_adds_a_credit_trading_pnl_entry_for_the_same_account() {
        Transaction transaction = Transaction.record(UUID.randomUUID(), "SELL", "Sell TEST");
        UUID accountId = UUID.randomUUID();

        // BTC 1개를 10000 에 매도, 평단가 8000 → 실현 이익 2000
        transaction.addBuyEntry(accountId, "KRW", krw(10000), BigDecimal.ONE, BigDecimal.ONE, "KRW");
        transaction.addSellEntry(accountId, "BTC", btc(1), BigDecimal.valueOf(10000), BigDecimal.ONE,
                BigDecimal.valueOf(8000), "KRW");

        assertThat(transaction.getEntries()).hasSize(3);
        TransactionEntry pnl = transaction.getEntries().get(2);
        assertThat(pnl.getAccountId()).as("손익은 고객의 손익이므로 고객 본인 계정에 기록한다").isEqualTo(accountId);
        assertThat(pnl.getEntryType()).isEqualTo(EntryType.CREDIT);
        assertThat(pnl.getAssetCode()).isEqualTo("REALIZED_PNL_TRADING");
        assertThat(pnl.getAmount().getAmount()).isEqualByComparingTo("2000");
        assertThat(pnl.getAmount().getCurrencyCode()).isEqualTo("KRW");
        assertThat(pnl.getQuantity().getAmount()).isEqualByComparingTo("2000");
        assertThat(pnl.getUnitPrice()).isEqualByComparingTo("1");
        assertThat(pnl.getExchangeRate()).isEqualByComparingTo("1");
        assertThat(pnl.getRealizedPnl().isZero()).as("손익 분개 자신은 다시 손익을 만들지 않는다").isTrue();
        transaction.verifyDoubleEntry();
    }

    @Test
    void addSellEntry_with_loss_adds_a_debit_trading_pnl_entry() {
        Transaction transaction = Transaction.record(UUID.randomUUID(), "SELL", "Sell TEST");
        UUID accountId = UUID.randomUUID();

        // BTC 1개를 10000 에 매도, 평단가 12000 → 실현 손실 2000
        transaction.addBuyEntry(accountId, "KRW", krw(10000), BigDecimal.ONE, BigDecimal.ONE, "KRW");
        transaction.addSellEntry(accountId, "BTC", btc(1), BigDecimal.valueOf(10000), BigDecimal.ONE,
                BigDecimal.valueOf(12000), "KRW");

        TransactionEntry pnl = transaction.getEntries().get(2);
        assertThat(pnl.getEntryType()).isEqualTo(EntryType.DEBIT);
        assertThat(pnl.getAssetCode()).isEqualTo("REALIZED_PNL_TRADING");
        assertThat(pnl.getAmount().getAmount()).as("손실도 양수 금액으로 기록하고 방향은 차변으로 나타낸다")
                .isEqualByComparingTo("2000");
        transaction.verifyDoubleEntry();
    }

    @Test
    void addSellEntry_of_fiat_records_the_pnl_as_fx() {
        Transaction transaction = Transaction.record(UUID.randomUUID(), "WITHDRAWAL", "Withdrawal TEST");
        UUID accountId = UUID.randomUUID();
        UUID clearing = UUID.randomUUID();
        Money usd = Money.of(BigDecimal.valueOf(100), AssetType.FIAT, "USD");

        // 1300 원에 들어온 USD 100 을 1400 원일 때 출금 → 환차익 10000
        transaction.addBuyEntry(clearing, "USD", usd, BigDecimal.ONE, BigDecimal.valueOf(1400), "KRW");
        transaction.addSellEntry(accountId, "USD", usd, BigDecimal.ONE, BigDecimal.valueOf(1400),
                BigDecimal.valueOf(1300), "KRW");

        TransactionEntry pnl = transaction.getEntries().get(2);
        assertThat(pnl.getAssetCode()).isEqualTo("REALIZED_PNL_FX");
        assertThat(pnl.getEntryType()).isEqualTo(EntryType.CREDIT);
        assertThat(pnl.getAmount().getAmount()).isEqualByComparingTo("10000");
        transaction.verifyDoubleEntry();
    }

    @Test
    void addSellEntry_without_pnl_adds_no_pnl_entry() {
        Transaction transaction = Transaction.record(UUID.randomUUID(), "BUY", "Buy TEST");
        UUID accountId = UUID.randomUUID();

        transaction.addBuyEntry(accountId, "BTC", btc(1), BigDecimal.valueOf(10000), BigDecimal.ONE, "KRW");
        // 기준 통화 결제: 평균 단가 = 환율 = 1 이라 손익이 없다
        transaction.addSellEntry(accountId, "KRW", krw(10000), BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, "KRW");

        assertThat(transaction.getEntries()).hasSize(2);
    }

    @Test
    void verifyDoubleEntry_should_not_count_the_realized_pnl_column_on_top_of_the_pnl_entry() {
        Transaction transaction = Transaction.record(UUID.randomUUID(), "SELL", "Sell TEST");
        UUID accountId = UUID.randomUUID();
        transaction.addBuyEntry(accountId, "KRW", krw(10000), BigDecimal.ONE, BigDecimal.ONE, "KRW");
        transaction.addSellEntry(accountId, "BTC", btc(1), BigDecimal.valueOf(10000), BigDecimal.ONE,
                BigDecimal.valueOf(8000), "KRW");

        // realized_pnl 컬럼(2000)은 참고값이다. 대차는 분개만으로 맞아야 한다.
        // 컬럼을 대변에 또 더하면 대변이 12000 이 되어 균형이 깨진다.
        assertThat(transaction.getEntries().get(1).getRealizedPnl().getAmount()).isEqualByComparingTo("2000");
        transaction.verifyDoubleEntry();

        // 손익 분개가 없으면 차변 10000 ≠ 대변 8000 이므로 검출되어야 한다.
        transaction.getEntries().removeIf(e -> e.getAssetCode().startsWith("REALIZED_PNL_"));
        assertThatThrownBy(transaction::verifyDoubleEntry)
                .isInstanceOf(DoubleEntryImbalanceException.class);
    }
}
