package com.github.raonjena99.multi_currency_ledger_service.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Kafka 컨슈머 오류 처리 정책을 정의하는 설정 클래스입니다.
 */
@Configuration
public class KafkaConfig {

    @Bean
    public DefaultErrorHandler errorHandler(KafkaOperations<?, ?> kafkaOperations) {

        // DLT(Dead Letter Topic)로 메시지를 보내는 Recoverer 생성.
        //
        // 목적지 결정에 주의: 이 에러 핸들러는 DLT 컨슈머의 리스너 컨테이너에도 적용된다.
        // 기본 규칙(topic + ".DLT")을 그대로 쓰면 DLT 컨슈머가 실패했을 때 아무도 구독하지 않는
        // "X.DLT.DLT" 토픽으로 발행되어, "잔고는 바뀌었는데 분개가 없는" 치명적 기록이 블랙홀로
        // 사라진다. 이미 DLT 인 토픽의 실패는 같은 DLT 토픽 뒤로 재적재해, 원인(대개 DB 장애)이
        // 복구될 때까지 유실 없이 순환하게 한다.
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                kafkaOperations,
                (consumerRecord, exception) -> {
                    String destination = consumerRecord.topic().endsWith(".DLT")
                            ? consumerRecord.topic()
                            : consumerRecord.topic() + ".DLT";
                    // 파티션 -1: 프로듀서가 파티션을 결정하게 위임한다.
                    return new org.apache.kafka.common.TopicPartition(destination, -1);
                });

        // DLT 발행 시 발생하는 예외를 삼켜 무한 재시도(Poison Pill)를 방지하는 래퍼 생성
        ConsumerRecordRecoverer safeRecoverer = (record, exception) -> {
            try {
                recoverer.accept(record, exception);
            } catch (Exception e) {
                org.slf4j.LoggerFactory.getLogger(KafkaConfig.class)
                    .error("DLT Publishing failed for record {}. Swallowing exception to prevent poison pill deadlock.", record, e);
            }
        };

        // 백오프 정책 설정: 1초 대기 후 최대 3번 재시도
        FixedBackOff backOff = new FixedBackOff(1000L, 3L);
        DefaultErrorHandler errorHandler = new DefaultErrorHandler(safeRecoverer, backOff);

        // 재시도해도 결과가 달라지지 않는 예외는 재시도 없이 즉시 DLT로 직행시킨다.
        //
        // 이 애플리케이션의 JSON 처리는 Jackson 3(tools.jackson)을 사용하므로 역직렬화 실패는
        // tools.jackson.core.JacksonException 으로 올라온다. Jackson 2 의
        // com.fasterxml.jackson.core.JsonProcessingException 을 등록하면 클래스패스에 두 버전이
        // 공존하는 탓에 컴파일은 되지만 분류가 절대 매칭되지 않아 재시도만 낭비된다.
        errorHandler.addNotRetryableExceptions(
                tools.jackson.core.JacksonException.class,
                IllegalArgumentException.class);

        return errorHandler;
    }
}
