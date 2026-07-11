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

/**
 * 정산(Reconciliation) 이벤트와 원장(Ledger) 간의 부패 방지 계층(ACL)을 담당하는 ReconciliationToLedgerAcl 클래스입니다.
 */
@Component
@RequiredArgsConstructor
public class ReconciliationToLedgerAcl {

    private final LedgerService ledgerService;
    
    private static final UUID SYSTEM_FEE_ACCOUNT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    /**
     * 정산 수수료 조정 이벤트를 처리하여 원장에 기록합니다.
     * @param event 정산 수수료 조정 이벤트 객체
     */
    @EventListener
    public void handle(ReconciliationFeeAdjustedEvent event) {
        Money feeDifference = event.feeDifference();

        // 수수료 차액을 시스템 수수료 계좌로 차감(FEE_DEDUCTION)하는 원장 기록 명령을 생성합니다.
        LedgerRecordingCommand command = new LedgerRecordingCommand(
                event.settlementId(),                           
                SYSTEM_FEE_ACCOUNT_ID,                          
                feeDifference.getAssetType().name(),             
                feeDifference.getCurrencyCode(),                                           
                "FEE_DEDUCTION",                               
                feeDifference,                                  
                Money.of("1", feeDifference.getAssetType(), feeDifference.getCurrencyCode()),
                BigDecimal.ONE,                                  
                Money.of("0", feeDifference.getAssetType(), feeDifference.getCurrencyCode()),
                false
        );

        ledgerService.recordDoubleEntry(command);
    }
}