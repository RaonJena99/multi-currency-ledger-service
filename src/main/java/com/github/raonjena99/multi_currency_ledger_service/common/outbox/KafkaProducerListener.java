package com.github.raonjena99.multi_currency_ledger_service.common.outbox;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.springframework.context.event.EventListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class KafkaProducerListener {

    private final KafkaTemplate<String, String> kafkaTemplate;

    @EventListener
    public void handleOutboxMessageEvent(OutboxMessageEvent event) {
        String topic = event.eventType();
        String payload = event.payload();

        log.info("Initiating Kafka message dispatch. Topic: [{}], Payload Size: {}", topic, payload.length());

        try {
            kafkaTemplate.send(topic, payload).get(3, TimeUnit.SECONDS);
            log.info("Successfully dispatched message to Kafka topic [{}]", topic);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Kafka dispatch interrupted", e);
        } catch (ExecutionException | TimeoutException e) {
            log.error("Critical connection or broker timeout during Kafka send on topic [{}]", topic, e);
            throw new RuntimeException("Triggering outbox failure logging due to Kafka infrastructure error", e);
        }
    }
}