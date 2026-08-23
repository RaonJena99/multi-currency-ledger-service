package com.github.raonjena99.multi_currency_ledger_service.common.outbox;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class OutboxRelayWorkerTest {

    @Mock
    private OutboxManager outboxManager;

    @Mock
    private OutboxMessageDispatcher messageDispatcher;

    @InjectMocks
    private OutboxRelayWorker worker;

    @Test
    void relayOutboxEvents_should_do_nothing_when_no_events() {
        when(outboxManager.claimUnprocessedEvents(100)).thenReturn(Collections.emptyList());

        worker.relayOutboxEvents();

        verify(outboxManager).claimUnprocessedEvents(100);
        org.mockito.Mockito.verifyNoMoreInteractions(outboxManager, messageDispatcher);
    }

    @Test
    void relayOutboxEvents_should_process_success_and_failures() {
        OutboxEvent event1 = new OutboxEvent("Account", "key1", "test-topic", "payload1", "corr-1");
        ReflectionTestUtils.setField(event1, "id", 1L);
        OutboxEvent event2 = new OutboxEvent("Account", "key2", "test-topic", "payload2", "corr-2");
        ReflectionTestUtils.setField(event2, "id", 2L);

        when(outboxManager.claimUnprocessedEvents(100)).thenReturn(Arrays.asList(event1, event2));

        when(messageDispatcher.dispatch(event1)).thenReturn(CompletableFuture.completedFuture(null));
        
        CompletableFuture<org.springframework.kafka.support.SendResult<String, String>> failedFuture = new CompletableFuture<>();
        failedFuture.completeExceptionally(new RuntimeException("Kafka error"));
        when(messageDispatcher.dispatch(event2)).thenReturn(failedFuture);

        worker.relayOutboxEvents();

        verify(messageDispatcher).dispatch(event1);
        verify(messageDispatcher).dispatch(event2);
        
        verify(outboxManager).updateResults(eq(Arrays.asList(1L)), eq(Arrays.asList(event2)));
    }

    @Test
    void relayOutboxEvents_should_handle_interruption() {
        OutboxEvent event1 = org.mockito.Mockito.mock(OutboxEvent.class);
        when(event1.getId()).thenReturn(1L);
        // Throw an exception in recordFailure to fail the exceptionally block!
        org.mockito.Mockito.doThrow(new RuntimeException("Simulated exception")).when(event1).recordFailure(org.mockito.ArgumentMatchers.anyString());
        
        when(outboxManager.claimUnprocessedEvents(100)).thenReturn(Arrays.asList(event1));
        
        CompletableFuture<org.springframework.kafka.support.SendResult<String, String>> failedFuture = new CompletableFuture<>();
        failedFuture.completeExceptionally(new RuntimeException("Kafka error"));
        when(messageDispatcher.dispatch(event1)).thenReturn(failedFuture);

        // Interrupt before running, which should be caught if we hit the catch block (we will, due to simulated exception)
        Thread.currentThread().interrupt();
        worker.relayOutboxEvents();
        Thread.interrupted(); // clear

        verify(outboxManager).updateResults(any(), any());
    }
    
    @Test
    void relayOutboxEvents_should_handle_generic_exception() {
        OutboxEvent event1 = org.mockito.Mockito.mock(OutboxEvent.class);
        when(event1.getId()).thenReturn(1L);
        // Throw an exception in recordFailure to fail the exceptionally block!
        org.mockito.Mockito.doThrow(new RuntimeException("Simulated exception")).when(event1).recordFailure(org.mockito.ArgumentMatchers.anyString());
        
        when(outboxManager.claimUnprocessedEvents(100)).thenReturn(Arrays.asList(event1));
        
        CompletableFuture<org.springframework.kafka.support.SendResult<String, String>> failedFuture = new CompletableFuture<>();
        failedFuture.completeExceptionally(new RuntimeException("Kafka error"));
        when(messageDispatcher.dispatch(event1)).thenReturn(failedFuture);

        worker.relayOutboxEvents();

        verify(outboxManager).updateResults(any(), any());
    }
}
