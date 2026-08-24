package com.github.raonjena99.multi_currency_ledger_service.common.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.DefaultErrorHandler;

class KafkaConfigTest {

    @Test
    void errorHandler_should_be_configured_correctly() {
        KafkaConfig config = new KafkaConfig();
        KafkaOperations<?, ?> operations = mock(KafkaOperations.class);
        
        DefaultErrorHandler handler = config.errorHandler(operations);
        
        assertThat(handler).isNotNull();
        
        assertThat(handler).isNotNull();
        
        // Mock the underlying operations to throw an exception when sending to DLT
        // to cover the catch block inside the safeRecoverer lambda
        org.mockito.Mockito.doThrow(new RuntimeException("DLT Publish Failed"))
            .when(operations).send(org.mockito.ArgumentMatchers.any(org.springframework.messaging.Message.class));
            
        ConsumerRecord<Object, Object> record = new ConsumerRecord<>("test-topic", 0, 0, "key", "value");
        Exception ex = new RuntimeException("Original error");
        
        // Trigger the error handler. In Spring Kafka, DefaultErrorHandler handles the record and exception.
        // We can call handleRemaining.
        org.springframework.kafka.listener.MessageListenerContainer container = org.mockito.Mockito.mock(org.springframework.kafka.listener.MessageListenerContainer.class);
        org.apache.kafka.clients.consumer.Consumer<?, ?> consumer = org.mockito.Mockito.mock(org.apache.kafka.clients.consumer.Consumer.class);
        
        // 재시도 불가로 등록한 예외는 Jackson 3(tools.jackson) 계열이어야 한다.
        Exception notRetryableEx = new tools.jackson.core.JacksonException("test") {};
        
        org.assertj.core.api.Assertions.assertThatCode(() -> 
            handler.handleRemaining(notRetryableEx, java.util.List.of(record), consumer, container)
        ).doesNotThrowAnyException();
        
        // Also test the happy path where send succeeds to cover the normal completion of the lambda
        org.mockito.Mockito.doReturn(java.util.concurrent.CompletableFuture.completedFuture(null))
            .when(operations).send(org.mockito.ArgumentMatchers.any(org.springframework.messaging.Message.class));
            
        org.assertj.core.api.Assertions.assertThatCode(() -> 
            handler.handleRemaining(notRetryableEx, java.util.List.of(record), consumer, container)
        ).doesNotThrowAnyException();
        
        // Also test the path where send throws an exception to cover the catch block in the lambda
        org.mockito.Mockito.doThrow(new RuntimeException("DLT publish failed"))
            .when(operations).send(org.mockito.ArgumentMatchers.any(org.springframework.messaging.Message.class));
            
        org.assertj.core.api.Assertions.assertThatCode(() -> 
            handler.handleRemaining(notRetryableEx, java.util.List.of(record), consumer, container)
        ).doesNotThrowAnyException();
    }
}
