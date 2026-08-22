package com.github.raonjena99.multi_currency_ledger_service.common.outbox;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxRelayWorker {

    private final OutboxManager outboxManager;
    private final OutboxMessageDispatcher messageDispatcher;

    @Scheduled(fixedDelay = 5000)
    // @SchedulerLock is intentionally removed to allow multiple nodes to process concurrently.
    // The DB-level SKIP LOCKED ensures they don't process the same records.
    public void relayOutboxEvents() {
        List<OutboxEvent> events = outboxManager.claimUnprocessedEvents(100);
        
        if (events.isEmpty()) return;

        log.debug("Claimed {} outbox events for processing", events.size());

        List<CompletableFuture<Void>> futures = events.stream().map(event -> 
            messageDispatcher.dispatch(event)
                .thenAccept(result -> {
                    event.markAsProcessed();
                    event.unlock();
                })
                .exceptionally(ex -> {
                    log.error("Failed to process OutboxEvent ID: {}", event.getId(), ex);
                    event.recordFailure(ex.getMessage());
                    event.unlock();
                    return null;
                })
        ).toList();

        // Wait for all messages to be dispatched
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        } catch (Exception e) {
            if (e.getCause() instanceof InterruptedException || Thread.currentThread().isInterrupted()) {
                log.warn("Worker thread interrupted. Stopping relay safely.");
                Thread.currentThread().interrupt(); // 인터럽트 상태 복원
            }
            log.error("Error waiting for async outbox dispatch", e);
        } finally {
            // 일괄(Bulk) 업데이트 처리
            outboxManager.updateEvents(events);
        }
    }
}