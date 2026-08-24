package com.github.raonjena99.multi_currency_ledger_service.common.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

@ExtendWith(MockitoExtension.class)
class OutboxMessageDispatcherTest {

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    @InjectMocks
    private OutboxMessageDispatcher dispatcher;

    @Test
    void dispatch_should_send_message_and_return_future() throws Exception {
        OutboxEvent event = new OutboxEvent("aggType", "key123", "topic1", "payload1", "corr123");
        
        CompletableFuture<SendResult<String, String>> future = new CompletableFuture<>();
        SendResult<String, String> resultMock = org.mockito.Mockito.mock(SendResult.class);
        future.complete(resultMock);
        
        when(kafkaTemplate.send("topic1", "key123", "payload1")).thenReturn(future);

        CompletableFuture<SendResult<String, String>> resultFuture = dispatcher.dispatch(event);
        
        assertThat(resultFuture.get()).isEqualTo(resultMock);
        verify(kafkaTemplate).send("topic1", "key123", "payload1");
    }

    @Test
    void dispatch_should_log_error_when_future_fails() {
        OutboxEvent event = new OutboxEvent("aggType", "key123", "topic1", "payload1", null);
        
        CompletableFuture<SendResult<String, String>> future = new CompletableFuture<>();
        future.completeExceptionally(new RuntimeException("Kafka error"));
        
        when(kafkaTemplate.send("topic1", "key123", "payload1")).thenReturn(future);

        CompletableFuture<SendResult<String, String>> resultFuture = dispatcher.dispatch(event);
        
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> resultFuture.get())
            .isInstanceOf(ExecutionException.class)
            .hasMessageContaining("Kafka error");
            
        verify(kafkaTemplate).send("topic1", "key123", "payload1");
    }
}
