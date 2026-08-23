package com.github.raonjena99.multi_currency_ledger_service.common.outbox;

import java.util.concurrent.CompletableFuture;

import org.slf4j.MDC;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 아웃박스 이벤트를 실제 Kafka(카프카)로 비동기 전송하는 디스패처.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxMessageDispatcher {

    private final KafkaTemplate<String, String> kafkaTemplate;

    /**
     * 카프카 토픽으로 페이로드를 비동기 전송하고 CompletableFuture를 반환합니다.
     */
    public CompletableFuture<SendResult<String, String>> dispatch(OutboxEvent event) {
        String topic = event.getEventType();
        String key = event.getAggregateId();
        String payload = event.getPayload();
        String correlationId = event.getCorrelationId();

        log.info("Initiating Kafka message dispatch. Topic: [{}], Payload Size: {}", topic, payload.length());

        if (correlationId != null) {
            MDC.put("correlationId", correlationId);
        }

        try {
            return kafkaTemplate.send(topic, key, payload)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Failed to dispatch message to Kafka topic [{}]", topic, ex);
                    } else {
                        log.info("Successfully dispatched message to Kafka topic [{}]", topic);
                    }
                });
        } finally {
            MDC.remove("correlationId");
        }
    }
}
