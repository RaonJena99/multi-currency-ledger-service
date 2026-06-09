package com.github.raonjena99.multi_currency_ledger_service.transaction.infrastructure.acl;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.github.raonjena99.multi_currency_ledger_service.account.domain.event.TradeExecutedEvent;
import com.github.raonjena99.multi_currency_ledger_service.common.domain.Money;
import com.github.raonjena99.multi_currency_ledger_service.common.model.AssetType;
import com.github.raonjena99.multi_currency_ledger_service.common.outbox.OutboxEvent;
import com.github.raonjena99.multi_currency_ledger_service.common.outbox.OutboxRepository;
import com.github.raonjena99.multi_currency_ledger_service.transaction.application.command.LedgerRecordingCommand;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.json.JsonMapper;

@Slf4j
@Component
@RequiredArgsConstructor
public class OrderToLedgerAcl {

    private final OutboxRepository outboxRepository;
    private final JsonMapper jsonMapper;

    @EventListener 
    public void persistOutboxEvent(TradeExecutedEvent externalEvent) {
        log.info("ACL: Translating and Persisting OutboxEvent for TradeID: {}", externalEvent.tradeId());

        try {
            // [수정] 상류 이벤트의 원시 타입(BigDecimal)을 하류 커맨드의 도메인 타입(Money)으로 명시적 번역
            LedgerRecordingCommand command = new LedgerRecordingCommand(
                externalEvent.tradeId(), // referenceTradeId로 매핑
                externalEvent.accountId(),
                externalEvent.assetCode(),
                externalEvent.fiatCode(),
                externalEvent.tradeType(),
                Money.of(externalEvent.quantity().toPlainString(), AssetType.valueOf(externalEvent.assetType())),
                Money.of(externalEvent.unitPrice().toPlainString(), AssetType.FIAT),
                externalEvent.exchangeRate(),
                Money.of(externalEvent.averageCost().toPlainString(), AssetType.FIAT)
            );

            // 규격에 맞게 변환된 Command를 직렬화
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
            throw new RuntimeException("Outbox serialization error", e);
        }
    }
}