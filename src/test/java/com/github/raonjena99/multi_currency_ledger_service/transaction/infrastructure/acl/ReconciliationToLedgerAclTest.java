package com.github.raonjena99.multi_currency_ledger_service.transaction.infrastructure.acl;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.github.raonjena99.multi_currency_ledger_service.common.domain.Money;
import com.github.raonjena99.multi_currency_ledger_service.common.model.AssetType;
import com.github.raonjena99.multi_currency_ledger_service.common.outbox.OutboxRepository;
import com.github.raonjena99.multi_currency_ledger_service.reconciliation.domain.event.ReconciliationFeeAdjustedEvent;

import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
class ReconciliationToLedgerAclTest {
    @Mock private OutboxRepository outboxRepository;
    @Mock private JsonMapper jsonMapper;
    @InjectMocks private ReconciliationToLedgerAcl acl;

    @Test
    void handle_firesRecordDoubleEntry() throws Exception {
        UUID settlementId = UUID.randomUUID();
        Money feeDifference = Money.of("10", AssetType.FIAT, "KRW");
        ReconciliationFeeAdjustedEvent event = ReconciliationFeeAdjustedEvent.of(settlementId, UUID.randomUUID(), UUID.randomUUID(), feeDifference);
        
        when(jsonMapper.writeValueAsString(any())).thenReturn("{}");

        acl.handle(event);
        
        verify(outboxRepository).save(any());
    }
}
