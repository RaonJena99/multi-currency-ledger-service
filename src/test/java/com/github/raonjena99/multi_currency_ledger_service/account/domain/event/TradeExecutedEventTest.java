package com.github.raonjena99.multi_currency_ledger_service.account.domain.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.github.raonjena99.multi_currency_ledger_service.common.domain.Money;
import com.github.raonjena99.multi_currency_ledger_service.common.model.AssetType;

class TradeExecutedEventTest {

    @Test
    void should_create_event_with_valid_parameters() {
        UUID tradeId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        Money quantity = Money.of("1.5", AssetType.CRYPTO, "BTC");
        
        TradeExecutedEvent event = new TradeExecutedEvent(
            tradeId, accountId, "BTC", AssetType.CRYPTO, "KRW", "KRW", com.github.raonjena99.multi_currency_ledger_service.common.model.TradeType.BUY, quantity.getAmount(), BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, false, null
        );
        
        assertThat(event.tradeId()).isEqualTo(tradeId);
        assertThat(event.accountId()).isEqualTo(accountId);
        assertThat(event.occurredAt()).isNotNull();
    }

    @Test
    void should_throw_if_tradeId_is_null() {
        UUID accountId = UUID.randomUUID();
        Money quantity = Money.of("1.5", AssetType.CRYPTO, "BTC");
        
        assertThatThrownBy(() -> new TradeExecutedEvent(
            null, accountId, "BTC", AssetType.CRYPTO, "KRW", "KRW", com.github.raonjena99.multi_currency_ledger_service.common.model.TradeType.BUY, quantity.getAmount(), BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, false, null
        )).isInstanceOf(NullPointerException.class);
    }

    @Test
    void should_throw_if_accountId_is_null() {
        UUID tradeId = UUID.randomUUID();
        Money quantity = Money.of("1.5", AssetType.CRYPTO, "BTC");
        
        assertThatThrownBy(() -> new TradeExecutedEvent(
            tradeId, null, "BTC", AssetType.CRYPTO, "KRW", "KRW", com.github.raonjena99.multi_currency_ledger_service.common.model.TradeType.BUY, quantity.getAmount(), BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, false, null
        )).isInstanceOf(NullPointerException.class);
    }
}
