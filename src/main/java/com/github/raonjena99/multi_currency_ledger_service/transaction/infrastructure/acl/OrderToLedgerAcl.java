package com.github.raonjena99.multi_currency_ledger_service.transaction.infrastructure.acl;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.github.raonjena99.multi_currency_ledger_service.account.domain.event.TradeExecutedEvent;
import com.github.raonjena99.multi_currency_ledger_service.common.domain.Money;
import com.github.raonjena99.multi_currency_ledger_service.common.exception.EventPublishingException;
import com.github.raonjena99.multi_currency_ledger_service.common.model.AssetType;
import com.github.raonjena99.multi_currency_ledger_service.common.outbox.OutboxEvent;
import com.github.raonjena99.multi_currency_ledger_service.common.outbox.OutboxMessageEvent;
import com.github.raonjena99.multi_currency_ledger_service.common.outbox.OutboxRepository;
import com.github.raonjena99.multi_currency_ledger_service.transaction.application.LedgerService;
import com.github.raonjena99.multi_currency_ledger_service.transaction.application.command.LedgerRecordingCommand;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.json.JsonMapper;

/**
 * 주문(Order) 도메인 이벤트와 원장(Ledger) 간의 부패 방지 계층(ACL)을 담당하는 OrderToLedgerAcl 클래스입니다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderToLedgerAcl {

    private final OutboxRepository outboxRepository;
    private final JsonMapper jsonMapper;
    private final LedgerService ledgerService;

    /**
     * TradeExecutedEvent(거래 실행 이벤트)를 수신하여 OutboxEvent(아웃박스 이벤트)로 변환 후 저장합니다.
     * @param externalEvent 외부 거래 실행 이벤트 객체
     */
    @EventListener 
    public void persistOutboxEvent(TradeExecutedEvent externalEvent) {
        log.info("ACL: Translating and Persisting OutboxEvent for TradeID: {}", externalEvent.tradeId());

        try {
            LedgerRecordingCommand command = new LedgerRecordingCommand(
                externalEvent.tradeId(),
                externalEvent.accountId(),
                externalEvent.assetCode(),
                externalEvent.fiatCode(),
                externalEvent.tradeType().name(),
                Money.of(externalEvent.quantity().toPlainString(), externalEvent.assetType(), externalEvent.fiatCode()),
                Money.of(externalEvent.unitPrice().toPlainString(), AssetType.FIAT, externalEvent.fiatCode()),
                externalEvent.exchangeRate(),
                Money.of(externalEvent.averageCost().toPlainString(), AssetType.FIAT, externalEvent.fiatCode()),
                externalEvent.isStaleRate()
            );

            String payload = jsonMapper.writeValueAsString(command);
            
            OutboxEvent outboxEvent = new OutboxEvent(
                "Ledger",
                externalEvent.accountId().toString(),
                "LedgerRecordingCommand",
                payload
            );
            
            outboxRepository.save(outboxEvent);
        } catch (Exception e) {
            log.error("Failed to translate/serialize TradeExecutedEvent", e);
            throw new EventPublishingException("Outbox serialization error", e);
        }
    }

    /**
     * 릴레이된 OutboxMessageEvent(아웃박스 메시지 이벤트)를 처리하여 원장에 복식 부기를 기록합니다.
     * @param msg 릴레이된 아웃박스 메시지 이벤트 객체
     */
    @EventListener
    public void handleOutboxRelay(OutboxMessageEvent msg) {
        try {
            if ("LedgerRecordingCommand".equals(msg.eventType())) {
                LedgerRecordingCommand command = jsonMapper.readValue(msg.payload(), LedgerRecordingCommand.class);
                ledgerService.recordDoubleEntry(command);
            }
        } catch (Exception e) {
            log.error("Failed to process relayed outbox message", e);
            throw new RuntimeException("Outbox relay processing error", e);
        }
    }
}