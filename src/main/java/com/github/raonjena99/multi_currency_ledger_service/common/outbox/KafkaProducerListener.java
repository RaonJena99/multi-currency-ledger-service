package com.github.raonjena99.multi_currency_ledger_service.common.outbox;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.MDC;
import org.springframework.context.event.EventListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 아웃박스 이벤트가 발행되었을 때 실제 Kafka(카프카)로 메시지를 전송하는 역할을 담당하는 KafkaProducerListener(카프카 프로듀서 리스너) 클래스입니다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KafkaProducerListener {

    private final KafkaTemplate<String, String> kafkaTemplate;

    /**
     * OutboxMessageEvent(아웃박스 메시지 이벤트)를 수신하여 설정된 Kafka 토픽으로 페이로드를 전송합니다.
     * 전송 중 타임아웃이나 예외 발생 시 RuntimeException을 던져 아웃박스 릴레이 워커가 실패 처리를 하도록 유도합니다.
     *
     * @param event 전송할 이벤트 토픽과 페이로드를 담은 OutboxMessageEvent 레코드
     */
    @EventListener
    public void handleOutboxMessageEvent(OutboxMessageEvent event) {
        String topic = event.eventType();
        String key = event.aggregateId();
        String payload = event.payload();

        log.info("Initiating Kafka message dispatch. Topic: [{}], Payload Size: {}", topic, payload.length());

        try {
            if (event.correlationId() != null) {
                MDC.put("correlationId", event.correlationId());
            }

            kafkaTemplate.send(topic, key, payload).get(3, TimeUnit.SECONDS);
            log.info("Successfully dispatched message to Kafka topic [{}]", topic);
        } catch (InterruptedException e) {
            // 현재 스레드의 인터럽트 상태를 복구하고 실행 중지
            Thread.currentThread().interrupt();
            throw new RuntimeException("Kafka dispatch interrupted", e);
        } catch (ExecutionException | TimeoutException e) {
            // 네트워크 순단 혹은 브로커 응답 지연 발생 시 Outbox 릴레이 루프에서 재시도할 수 있도록 예외 전파
            log.error("Critical connection or broker timeout during Kafka send on topic [{}]", topic, e);
            throw new RuntimeException("Triggering outbox failure logging due to Kafka infrastructure error", e);
        } finally {
            MDC.remove("correlationId"); 
        }
    }
}