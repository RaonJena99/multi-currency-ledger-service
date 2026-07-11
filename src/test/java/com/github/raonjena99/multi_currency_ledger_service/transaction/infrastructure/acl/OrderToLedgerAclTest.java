package com.github.raonjena99.multi_currency_ledger_service.transaction.infrastructure.acl;

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
import com.github.raonjena99.multi_currency_ledger_service.common.outbox.OutboxMessageEvent;
import com.github.raonjena99.multi_currency_ledger_service.common.outbox.OutboxRepository;
import com.github.raonjena99.multi_currency_ledger_service.transaction.application.LedgerService;
import com.github.raonjena99.multi_currency_ledger_service.transaction.application.command.LedgerRecordingCommand;

import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
class OrderToLedgerAclTest {

    @Mock private OutboxRepository outboxRepository;
    @Mock private JsonMapper jsonMapper;
    @Mock private LedgerService ledgerService;

    @InjectMocks private OrderToLedgerAcl acl;

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

    @Test
    void handleOutboxRelay() throws Exception {
        OutboxMessageEvent msg = new OutboxMessageEvent("LedgerRecordingCommand", "{}");
        LedgerRecordingCommand cmd = org.mockito.Mockito.mock(LedgerRecordingCommand.class);
        when(jsonMapper.readValue("{}", LedgerRecordingCommand.class)).thenReturn(cmd);
        acl.handleOutboxRelay(msg);
        verify(ledgerService).recordDoubleEntry(cmd);
    }
    
    @Test
    void handleOutboxRelay_ignore() throws Exception {
        OutboxMessageEvent msg = new OutboxMessageEvent("OtherCommand", "{}");
        acl.handleOutboxRelay(msg);
        verify(ledgerService, org.mockito.Mockito.never()).recordDoubleEntry(any());
    }

    @Test
    void handleOutboxRelay_exception() throws Exception {
        OutboxMessageEvent msg = new OutboxMessageEvent("LedgerRecordingCommand", "{}");
        when(jsonMapper.readValue("{}", LedgerRecordingCommand.class)).thenThrow(new RuntimeException("error"));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> acl.handleOutboxRelay(msg))
            .isInstanceOf(RuntimeException.class);
    }
}