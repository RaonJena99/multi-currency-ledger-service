package com.github.raonjena99.multi_currency_ledger_service.account.infrastructure.acl;

import org.slf4j.MDC;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.github.raonjena99.multi_currency_ledger_service.account.domain.event.TradeExecutedEvent;
import com.github.raonjena99.multi_currency_ledger_service.common.domain.Money;
import com.github.raonjena99.multi_currency_ledger_service.common.model.AssetType;
import com.github.raonjena99.multi_currency_ledger_service.common.outbox.OutboxEvent;
import com.github.raonjena99.multi_currency_ledger_service.common.outbox.OutboxRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.json.JsonMapper;

@Slf4j
@Component
@RequiredArgsConstructor
public class AccountOutboxAcl {

    private final OutboxRepository outboxRepository;
    private final JsonMapper jsonMapper;
    
    // 모듈 간 강결합(Modulith Violation) 방지를 위해 내부 DTO 레코드 선언
    record LedgerRecordingPayload(
        java.util.UUID tradeId,
        java.util.UUID accountId,
        String targetAssetCode,
        String paymentCurrency,
        String tradeType,
        Money quantity,
        Money unitPrice,
        java.math.BigDecimal exchangeRate,
        Money averageCost,
        boolean isStaleRate
    ) {}
    
    /**
     * TradeExecutedEvent(거래 실행 이벤트)를 수신하여 OutboxEvent(아웃박스 이벤트)로 변환 후 저장합니다.
     * @param externalEvent
     */
    @EventListener
    public void persistOutboxEvent(TradeExecutedEvent externalEvent) {

        log.info("Account ACL: Persisting OutboxEvent for TradeID: {}", externalEvent.tradeId());
        
        try {
            String correlationId = MDC.get("correlationId");
            
            // 내부 DTO로 변환
            LedgerRecordingPayload payload = new LedgerRecordingPayload(
                externalEvent.tradeId(), externalEvent.accountId(), externalEvent.assetCode(), externalEvent.fiatCode(),
                externalEvent.tradeType().name(),
                Money.of(externalEvent.quantity().toPlainString(), externalEvent.assetType(), externalEvent.fiatCode()),
                Money.of(externalEvent.unitPrice().toPlainString(), AssetType.FIAT, externalEvent.fiatCode()),
                externalEvent.exchangeRate(),
                Money.of(externalEvent.averageCost().toPlainString(), AssetType.FIAT, externalEvent.fiatCode()),
                externalEvent.isStaleRate()
            );

            // Outbox 테이블에 저장
            OutboxEvent outboxEvent = new OutboxEvent("Ledger", externalEvent.accountId().toString(), "LedgerRecordingCommand", jsonMapper.writeValueAsString(payload), correlationId);
            outboxRepository.save(outboxEvent);
        } catch (Exception e) {
            log.error("Failed to translate/serialize TradeExecutedEvent to Outbox", e);
            throw new RuntimeException("Outbox serialization error", e);
        }
    }
}
