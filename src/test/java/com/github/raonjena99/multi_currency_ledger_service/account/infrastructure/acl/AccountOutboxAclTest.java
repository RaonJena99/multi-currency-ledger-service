package com.github.raonjena99.multi_currency_ledger_service.account.infrastructure.acl;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.github.raonjena99.multi_currency_ledger_service.account.domain.event.TradeExecutedEvent;
import com.github.raonjena99.multi_currency_ledger_service.common.outbox.OutboxRepository;

import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
class AccountOutboxAclTest {

    @Mock private OutboxRepository outboxRepository;
    @Mock private JsonMapper jsonMapper;

    @InjectMocks private AccountOutboxAcl acl;

    @Test
    void persistOutboxEvent() throws Exception {
        TradeExecutedEvent event = new TradeExecutedEvent(UUID.randomUUID(), UUID.randomUUID(), "KRW", com.github.raonjena99.multi_currency_ledger_service.common.model.AssetType.FIAT, "USD", com.github.raonjena99.multi_currency_ledger_service.common.model.TradeType.BUY, BigDecimal.TEN, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, false, java.time.OffsetDateTime.now());
        when(jsonMapper.writeValueAsString(any())).thenReturn("{}");
        acl.persistOutboxEvent(event);
        verify(outboxRepository).save(any());
    }

    @Test
    void persistOutboxEvent_exception() throws Exception {
        TradeExecutedEvent event = new TradeExecutedEvent(UUID.randomUUID(), UUID.randomUUID(), "KRW", com.github.raonjena99.multi_currency_ledger_service.common.model.AssetType.FIAT, "USD", com.github.raonjena99.multi_currency_ledger_service.common.model.TradeType.BUY, BigDecimal.TEN, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, false, java.time.OffsetDateTime.now());
        when(jsonMapper.writeValueAsString(any())).thenThrow(new RuntimeException("error"));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> acl.persistOutboxEvent(event))
            .isInstanceOf(RuntimeException.class);
    }
}
