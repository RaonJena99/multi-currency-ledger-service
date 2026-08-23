package com.github.raonjena99.multi_currency_ledger_service.common.outbox;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

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
    // @SchedulerLock is intentionally removed to allow multiple nodes to process
    // concurrently.
    // The DB-level SKIP LOCKED ensures they don't process the same records.
    public void relayOutboxEvents() {
        List<OutboxEvent> events = outboxManager.claimUnprocessedEvents(100);

        if (events.isEmpty())
            return;

        // 성공한 ID와 실패한 이벤트를 분리해서 담을 스레드 안전한(Thread-Safe) 리스트
        List<Long> successIds = new CopyOnWriteArrayList<>();
        List<OutboxEvent> failedEvents = new CopyOnWriteArrayList<>();

        List<CompletableFuture<Void>> futures = events.stream().map(event -> messageDispatcher.dispatch(event)
                .thenAccept(result -> {
                    // 성공 시 메모리 객체를 수정하지 않고 ID만 수집합니다.
                    successIds.add(event.getId());
                })
                .exceptionally(ex -> {
                    log.error("Failed to process OutboxEvent ID: {}", event.getId(), ex);
                    // 실패 시 에러 사유를 객체에 기록하고 실패 리스트에 담습니다.
                    event.recordFailure(ex.getMessage());
                    event.unlock();
                    failedEvents.add(event);
                    return null;
                })).toList();

        // 모든 발송 대기
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
            outboxManager.updateResults(successIds, failedEvents);
        }
    }
}