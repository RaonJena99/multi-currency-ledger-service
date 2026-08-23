package com.github.raonjena99.multi_currency_ledger_service.transaction.infrastructure.acl;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.github.raonjena99.multi_currency_ledger_service.common.domain.Money;
import com.github.raonjena99.multi_currency_ledger_service.common.model.AssetType;
import com.github.raonjena99.multi_currency_ledger_service.common.outbox.OutboxEvent;
import com.github.raonjena99.multi_currency_ledger_service.common.outbox.OutboxRepository;
import com.github.raonjena99.multi_currency_ledger_service.reconciliation.domain.event.ReconciliationFeeAdjustedEvent;

import tools.jackson.databind.json.JsonMapper;

class ReconciliationToLedgerAclTest {

    @Test
    void handle_should_save_outbox_event() throws Exception {
        OutboxRepository repository = mock(OutboxRepository.class);
        JsonMapper mapper = mock(JsonMapper.class);
        ReconciliationToLedgerAcl acl = new ReconciliationToLedgerAcl(repository, mapper);
        
        when(mapper.writeValueAsString(any())).thenReturn("{}");
        
        ReconciliationFeeAdjustedEvent event = ReconciliationFeeAdjustedEvent.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), Money.of("10", AssetType.FIAT, "KRW"));
        
        acl.handle(event);
        
        verify(repository).save(any(OutboxEvent.class));
    }

    @Test
    void handle_should_throw_on_serialization_error() throws Exception {
        OutboxRepository repository = mock(OutboxRepository.class);
        JsonMapper mapper = mock(JsonMapper.class);
        ReconciliationToLedgerAcl acl = new ReconciliationToLedgerAcl(repository, mapper);
        
        when(mapper.writeValueAsString(any())).thenThrow(new RuntimeException("error"));
        
        ReconciliationFeeAdjustedEvent event = ReconciliationFeeAdjustedEvent.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), Money.of("10", AssetType.FIAT, "KRW"));
        
        assertThatThrownBy(() -> acl.handle(event))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("Outbox serialization error");
    }
}
