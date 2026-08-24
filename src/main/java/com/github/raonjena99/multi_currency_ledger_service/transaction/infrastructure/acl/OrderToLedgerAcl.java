package com.github.raonjena99.multi_currency_ledger_service.transaction.infrastructure.acl;

import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import com.github.raonjena99.multi_currency_ledger_service.common.telemetry.CorrelationIdFilter;
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
     *
     * <p>Kafka 헤더의 correlation id 를 MDC 로 복원해야 컨슈머 쪽 로그까지 추적이 이어집니다.
     * 이 복원이 없으면 HTTP 진입점부터 컨슈머까지의 분산 추적이 발행 지점에서 끊깁니다.
     *
     * @param payload       원장 기록 커맨드 JSON
     * @param correlationId 발행 측에서 전달된 분산 추적 식별자
     */
    @KafkaListener(topics = "LedgerRecordingCommand", groupId = "ledger-recording-group")
    public void consumeLedgerCommand(
            String payload,
            @Header(name = CorrelationIdFilter.MDC_KEY, required = false) String correlationId) {

        if (correlationId != null) {
            MDC.put(CorrelationIdFilter.MDC_KEY, correlationId);
        }

        try {
            log.info("Kafka Consumer: Received Ledger message: {}", payload);

            LedgerRecordingCommand command = jsonMapper.readValue(payload, LedgerRecordingCommand.class);
            ledgerService.recordDoubleEntry(command);
        } catch (Exception e) {
            // 예외를 그대로 올려 DefaultErrorHandler 의 재시도/DLT 정책이 적용되게 한다.
            // RuntimeException 으로 감싸면 재시도 불가 예외 분류가 원인 체인에서만 발견되므로
            // 원본 타입을 최대한 보존한다.
            log.error("Failed to process consumed Kafka message", e);
            throw e;
        } finally {
            MDC.remove(CorrelationIdFilter.MDC_KEY);
        }
    }
}
