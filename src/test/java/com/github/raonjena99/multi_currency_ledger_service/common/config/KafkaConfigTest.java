package com.github.raonjena99.multi_currency_ledger_service.common.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

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

        ConsumerRecord<Object, Object> record = new ConsumerRecord<>("test-topic", 0, 0, "key", "value");
        org.springframework.kafka.listener.MessageListenerContainer container = org.mockito.Mockito.mock(org.springframework.kafka.listener.MessageListenerContainer.class);
        org.apache.kafka.clients.consumer.Consumer<?, ?> consumer = org.mockito.Mockito.mock(org.apache.kafka.clients.consumer.Consumer.class);

        // 재시도 불가로 등록한 예외는 Jackson 3(tools.jackson) 계열이어야 한다.
        Exception notRetryableEx = new tools.jackson.core.JacksonException("test") {};

        // DLT 발행이 성공하면 레코드가 복구 처리되어 예외 없이 끝난다.
        org.mockito.Mockito.doReturn(java.util.concurrent.CompletableFuture.completedFuture(null))
            .when(operations).send(org.mockito.ArgumentMatchers.any(org.apache.kafka.clients.producer.ProducerRecord.class));

        org.assertj.core.api.Assertions.assertThatCode(() ->
            handler.handleRemaining(notRetryableEx, java.util.List.of(record), consumer, container)
        ).doesNotThrowAnyException();

        // DLT 발행이 실패하면 예외를 삼키지 않고 전파해야 한다. 삼키면 오프셋이 커밋되어
        // 원장 커맨드가 DLT 에도 남지 않고 유실된다. 전파하면 같은 레코드를 다시 시도한다.
        org.mockito.Mockito.doThrow(new RuntimeException("DLT publish failed"))
            .when(operations).send(org.mockito.ArgumentMatchers.any(org.apache.kafka.clients.producer.ProducerRecord.class));

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
            handler.handleRemaining(notRetryableEx, java.util.List.of(record), consumer, container)
        ).isInstanceOf(RuntimeException.class);
    }
}
