package com.github.raonjena99.multi_currency_ledger_service.transaction.infrastructure.acl;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.github.raonjena99.multi_currency_ledger_service.transaction.application.LedgerService;
import com.github.raonjena99.multi_currency_ledger_service.transaction.application.command.LedgerRecordingCommand;

import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
class OrderToLedgerAclTest {

    @Mock private JsonMapper jsonMapper;
    @Mock private LedgerService ledgerService;

    @InjectMocks private OrderToLedgerAcl acl;

    @Test
    void consumeLedgerCommand() throws Exception {
        String payload = "{}";
        LedgerRecordingCommand cmd = org.mockito.Mockito.mock(LedgerRecordingCommand.class);
        when(jsonMapper.readValue(payload, LedgerRecordingCommand.class)).thenReturn(cmd);
        acl.consumeLedgerCommand(payload);
        verify(ledgerService).recordDoubleEntry(cmd);
    }

    @Test
    void consumeLedgerCommand_exception() throws Exception {
        String payload = "{}";
        when(jsonMapper.readValue(payload, LedgerRecordingCommand.class)).thenThrow(new RuntimeException("error"));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> acl.consumeLedgerCommand(payload))
            .isInstanceOf(RuntimeException.class);
    }
}