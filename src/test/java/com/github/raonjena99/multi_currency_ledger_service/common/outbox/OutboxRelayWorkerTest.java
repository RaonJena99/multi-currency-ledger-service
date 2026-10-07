package com.github.raonjena99.multi_currency_ledger_service.common.outbox;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class OutboxRelayWorkerTest {

    @Mock
    private OutboxManager outboxManager;

    @Mock
    private OutboxMessageDispatcher messageDispatcher;

    private OutboxRelayWorker worker;

    /** 기존 테스트는 배치 100, 실행당 1배치(한 번만 가져옴)로 예전 동작을 검증한다. */
    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        worker = new OutboxRelayWorker(outboxManager, messageDispatcher, 100, 1);
    }

    private static OutboxEvent event(long id) {
        OutboxEvent event = new OutboxEvent("Account", "key" + id, "test-topic", "payload" + id, "corr-" + id);
        ReflectionTestUtils.setField(event, "id", id);
        return event;
    }

    @Test
    void relayOutboxEvents_should_keep_claiming_while_batches_are_full() {
        // 배치가 꽉 찼으면 더 남아 있을 가능성이 크므로 다음 주기를 기다리지 않고 바로 가져온다.
        worker = new OutboxRelayWorker(outboxManager, messageDispatcher, 2, 10);
        when(outboxManager.claimUnprocessedEvents(2)).thenReturn(
                Arrays.asList(event(1), event(2)),
                Arrays.asList(event(3), event(4)),
                Arrays.asList(event(5)));
        when(messageDispatcher.dispatch(any())).thenReturn(CompletableFuture.completedFuture(null));

        worker.relayOutboxEvents();

        verify(outboxManager, org.mockito.Mockito.times(3)).claimUnprocessedEvents(2);
        verify(outboxManager, org.mockito.Mockito.times(3)).updateResults(any(), any());
    }

    @Test
    void relayOutboxEvents_should_stop_at_max_batches_per_run() {
        // 한 번의 실행이 스케줄러 스레드를 끝없이 붙잡지 않도록 배치 수에 상한을 둔다.
        worker = new OutboxRelayWorker(outboxManager, messageDispatcher, 1, 3);
        when(outboxManager.claimUnprocessedEvents(1)).thenAnswer(inv -> Arrays.asList(event(System.nanoTime())));
        when(messageDispatcher.dispatch(any())).thenReturn(CompletableFuture.completedFuture(null));

        worker.relayOutboxEvents();

        verify(outboxManager, org.mockito.Mockito.times(3)).claimUnprocessedEvents(1);
    }

    @Test
    void relayOutboxEvents_should_stop_when_a_full_batch_had_failures() {
        // 실패가 섞였다면 브로커 장애일 수 있다. 이어서 가져오면 남은 이벤트 전부가 같은 실패를 겪으며
        // 실행 시간만 길어지므로 다음 주기로 미룬다(실패 건은 백오프로 재시도된다).
        worker = new OutboxRelayWorker(outboxManager, messageDispatcher, 2, 10);
        when(outboxManager.claimUnprocessedEvents(2)).thenReturn(Arrays.asList(event(1), event(2)));
        CompletableFuture<org.springframework.kafka.support.SendResult<String, String>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new RuntimeException("Kafka down"));
        when(messageDispatcher.dispatch(any())).thenReturn(CompletableFuture.completedFuture(null), failed);

        worker.relayOutboxEvents();

        verify(outboxManager, org.mockito.Mockito.times(1)).claimUnprocessedEvents(2);
    }

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
