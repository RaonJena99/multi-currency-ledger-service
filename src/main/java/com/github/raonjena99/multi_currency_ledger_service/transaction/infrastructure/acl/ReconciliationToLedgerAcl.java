package com.github.raonjena99.multi_currency_ledger_service.transaction.infrastructure.acl;

import java.math.BigDecimal;
import java.util.UUID;

import org.slf4j.MDC;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.github.raonjena99.multi_currency_ledger_service.common.domain.Money;
import com.github.raonjena99.multi_currency_ledger_service.common.outbox.OutboxEvent;
import com.github.raonjena99.multi_currency_ledger_service.common.outbox.OutboxRepository;
import com.github.raonjena99.multi_currency_ledger_service.reconciliation.domain.event.ReconciliationFeeAdjustedEvent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.json.JsonMapper;

/**
 * 정산(Reconciliation) 이벤트와 원장(Ledger) 간의 부패 방지 계층(ACL)을 담당하는 ReconciliationToLedgerAcl 클래스입니다.
 */

@Slf4j
@Component
@RequiredArgsConstructor
public class ReconciliationToLedgerAcl {

    private final OutboxRepository outboxRepository;
    private final JsonMapper jsonMapper;
    
    private static final UUID SYSTEM_FEE_ACCOUNT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    record LedgerRecordingPayload(
        UUID settlementId, UUID accountId, String targetAssetCode, String paymentCurrency,
        String tradeType, Money quantity, Money unitPrice, BigDecimal exchangeRate, Money averageCost, boolean isStaleRate
    ) {}

    /**
     * 정산 수수료 조정 이벤트를 처리하여 원장에 기록합니다.
     * @param event 정산 수수료 조정 이벤트 객체
     */
    @EventListener
    public void handle(ReconciliationFeeAdjustedEvent event) {
        Money fee = event.feeDifference();
        String correlationId = MDC.get("correlationId");

        try {
            LedgerRecordingPayload payload = new LedgerRecordingPayload(
                event.settlementId(), SYSTEM_FEE_ACCOUNT_ID, fee.getAssetType().name(), fee.getCurrencyCode(),
                "FEE_DEDUCTION", fee, Money.of("1", fee.getAssetType(), fee.getCurrencyCode()),
                BigDecimal.ONE, Money.of("0", fee.getAssetType(), fee.getCurrencyCode()), false
            );

            OutboxEvent outboxEvent = new OutboxEvent(
                "Ledger", SYSTEM_FEE_ACCOUNT_ID.toString(), "LedgerRecordingCommand", 
                jsonMapper.writeValueAsString(payload), correlationId
            );
            
            // 트랜잭션 내에서 아웃박스 테이블에 기록 완료
            outboxRepository.save(outboxEvent);
            log.info("Reconciliation ACL: Persisted OutboxEvent for SettlementID: {}", event.settlementId());
        } catch (Exception e) {
            log.error("Failed to serialize ReconciliationFeeAdjustedEvent to Outbox", e);
            throw new RuntimeException("Outbox serialization error", e);
        }
    }
}