package com.github.raonjena99.multi_currency_ledger_service.common.outbox;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

class KafkaProducerListenerTest {

    @Test
    void handleOutboxMessageEvent_success() {
        KafkaTemplate<String, String> template = mock(KafkaTemplate.class);
        CompletableFuture<SendResult<String, String>> future = CompletableFuture.completedFuture(null);
        when(template.send(anyString(), anyString())).thenReturn(future);

        KafkaProducerListener listener = new KafkaProducerListener(template);
        listener.handleOutboxMessageEvent(new OutboxMessageEvent("topic", "payload"));

        verify(template).send("topic", "payload");
    }

    @Test
    void handleOutboxMessageEvent_executionException() {
        KafkaTemplate<String, String> template = mock(KafkaTemplate.class);
        CompletableFuture<SendResult<String, String>> future = new CompletableFuture<>();
        future.completeExceptionally(new RuntimeException("failed"));
        when(template.send(anyString(), anyString())).thenReturn(future);

        KafkaProducerListener listener = new KafkaProducerListener(template);
        assertThatThrownBy(() -> listener.handleOutboxMessageEvent(new OutboxMessageEvent("topic", "payload")))
            .isInstanceOf(RuntimeException.class);
    }
}
