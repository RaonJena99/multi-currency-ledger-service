package com.github.raonjena99.multi_currency_ledger_service.transaction.infrastructure.acl;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.github.raonjena99.multi_currency_ledger_service.common.domain.Money;
import com.github.raonjena99.multi_currency_ledger_service.common.model.AssetType;
import com.github.raonjena99.multi_currency_ledger_service.reconciliation.domain.event.ReconciliationFeeAdjustedEvent;
import com.github.raonjena99.multi_currency_ledger_service.transaction.application.LedgerService;

@ExtendWith(MockitoExtension.class)
class ReconciliationToLedgerAclTest {
    @Mock private LedgerService ledgerService;
    @InjectMocks private ReconciliationToLedgerAcl acl;

    @Test
    void handle_firesRecordDoubleEntry() {
        UUID settlementId = UUID.randomUUID();
        Money feeDifference = Money.of("10", AssetType.FIAT, "KRW");
        ReconciliationFeeAdjustedEvent event = ReconciliationFeeAdjustedEvent.of(settlementId, UUID.randomUUID(), UUID.randomUUID(), feeDifference);
        
        acl.handle(event);
        
        verify(ledgerService).recordDoubleEntry(any());
    }
}
