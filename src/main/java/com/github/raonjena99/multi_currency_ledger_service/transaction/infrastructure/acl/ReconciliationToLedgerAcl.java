package com.github.raonjena99.multi_currency_ledger_service.transaction.infrastructure.acl;

import java.math.BigDecimal;
import java.util.UUID;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.github.raonjena99.multi_currency_ledger_service.common.domain.Money;
import com.github.raonjena99.multi_currency_ledger_service.reconciliation.domain.event.ReconciliationFeeAdjustedEvent;
import com.github.raonjena99.multi_currency_ledger_service.transaction.application.LedgerService;
import com.github.raonjena99.multi_currency_ledger_service.transaction.application.command.LedgerRecordingCommand;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class ReconciliationToLedgerAcl {

    private final LedgerService ledgerService;
    
    private static final UUID SYSTEM_FEE_ACCOUNT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @EventListener
    public void handle(ReconciliationFeeAdjustedEvent event) {
        Money feeDifference = event.feeDifference();

        LedgerRecordingCommand command = new LedgerRecordingCommand(
                event.settlementId(),                           
                SYSTEM_FEE_ACCOUNT_ID,                          
                feeDifference.getAssetType().name(),             
                "KRW",                                           
                "FEE_DEDUCTION",                               
                feeDifference,                                  
                Money.of("1", feeDifference.getAssetType()), 
                BigDecimal.ONE,                                  
                Money.of("0", feeDifference.getAssetType()),
                false
        );

        ledgerService.recordDoubleEntry(command);
    }
}