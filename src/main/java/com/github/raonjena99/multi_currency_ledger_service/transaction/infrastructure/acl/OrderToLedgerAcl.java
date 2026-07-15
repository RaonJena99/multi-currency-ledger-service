package com.github.raonjena99.multi_currency_ledger_service.transaction.infrastructure.acl;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

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
    private final JsonMapper jsonMapper;
    private final LedgerService ledgerService;

    /**
     * Kafka 메시지를 수신하여 원장 기록을 수행합니다.
     * @param payload
     */
    @KafkaListener(topics = "LedgerRecordingCommand", groupId = "ledger-recording-group")
    public void consumeLedgerCommand(String payload) {

        log.info("Kafka Consumer: Received Ledger message: {}", payload);

        try {
            LedgerRecordingCommand command = jsonMapper.readValue(payload, LedgerRecordingCommand.class);
            ledgerService.recordDoubleEntry(command);
        } catch (Exception e) {
            log.error("Failed to process consumed Kafka message", e);
            throw new RuntimeException("Kafka message processing error", e);
        }
    }

}