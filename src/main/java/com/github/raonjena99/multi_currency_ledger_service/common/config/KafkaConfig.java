package com.github.raonjena99.multi_currency_ledger_service.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

import com.fasterxml.jackson.core.JsonProcessingException;

@Configuration
public class KafkaConfig {
    @Bean
    public DefaultErrorHandler errorHandler(KafkaOperations<?, ?> kafkaOperations) {
        
        // DLT(Dead Letter Topic)로 메시지를 보내는 Recoverer 생성
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafkaOperations);
        
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
        // 기존 recoverer 대신 safeRecoverer 를 주입!
        DefaultErrorHandler errorHandler = new DefaultErrorHandler(safeRecoverer, backOff);
        // 재시도해도 의미 없는 예외는 재시도 없이 즉시 DLT로 직행
        errorHandler.addNotRetryableExceptions(JsonProcessingException.class);
        
        return errorHandler;
    }
}