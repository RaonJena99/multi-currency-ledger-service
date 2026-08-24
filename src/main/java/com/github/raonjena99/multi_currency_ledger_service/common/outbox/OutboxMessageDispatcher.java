package com.github.raonjena99.multi_currency_ledger_service.common.outbox;

import java.util.concurrent.CompletableFuture;

import org.slf4j.MDC;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import com.github.raonjena99.multi_currency_ledger_service.common.telemetry.CorrelationIdFilter;

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
     *
     * <p>MDC 에 correlation id 를 심는 이유는 KafkaCorrelationInterceptor 가 onSend 시점에
     * 호출 스레드의 MDC 를 읽어 Kafka 헤더로 옮기기 때문입니다. 따라서 키는
     * {@link CorrelationIdFilter#MDC_KEY} 와 반드시 같아야 합니다. 다른 리터럴을 쓰면
     * 헤더가 조용히 붙지 않아 추적 체인이 끊깁니다.
     *
     * @param event 전송할 아웃박스 이벤트
     * @return 전송 결과 Future
     */
    public CompletableFuture<SendResult<String, String>> dispatch(OutboxEvent event) {
        String topic = event.getEventType();
        String key = event.getAggregateId();
        String payload = event.getPayload();
        String correlationId = event.getCorrelationId();

        log.info("Initiating Kafka message dispatch. Topic: [{}], Payload Size: {}", topic, payload.length());

        // 릴레이 워커 스레드에 이전 값이 남아 있을 수 있으므로 원래 값을 보존한 뒤 복원한다.
        String previousCorrelationId = MDC.get(CorrelationIdFilter.MDC_KEY);
        if (correlationId != null) {
            MDC.put(CorrelationIdFilter.MDC_KEY, correlationId);
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
            if (previousCorrelationId != null) {
                MDC.put(CorrelationIdFilter.MDC_KEY, previousCorrelationId);
            } else {
                MDC.remove(CorrelationIdFilter.MDC_KEY);
            }
        }
    }
}
