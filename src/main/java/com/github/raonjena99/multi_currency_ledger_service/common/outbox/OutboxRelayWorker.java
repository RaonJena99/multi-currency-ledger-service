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

        // 발송 준비와 대기를 모두 try 안에서 수행한다.
        //
        // KafkaTemplate.send 는 버퍼 고갈, 직렬화 실패, 메타데이터 타임아웃 등에서 동기 예외를
        // 던질 수 있다. 이 루프가 try 밖에 있으면 예외가 finally 를 건너뛰어 이미 성공한 이벤트의
        // successIds 가 버려지고(중복 발행), 선점된 100건이 실패 기록 없이 5분간 잠긴 채 남는다.
        try {
            List<CompletableFuture<Void>> futures = events.stream()
                    .map(this::dispatchSafely)
                    .map(stage -> stage.futureFor(successIds, failedEvents))
                    .toList();

            // 모든 발송 대기
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

    /**
     * 개별 이벤트의 발송을 시도하고, 동기 예외까지 결과로 흡수합니다.
     * 한 건의 발송 실패가 나머지 이벤트의 결과 반영을 막지 않도록 합니다.
     */
    private DispatchStage dispatchSafely(OutboxEvent event) {
        try {
            return new DispatchStage(event, messageDispatcher.dispatch(event), null);
        } catch (Exception e) {
            log.error("Synchronous failure while dispatching OutboxEvent ID: {}", event.getId(), e);
            return new DispatchStage(event, null, e);
        }
    }

    private record DispatchStage(OutboxEvent event,
                                 CompletableFuture<org.springframework.kafka.support.SendResult<String, String>> future,
                                 Exception synchronousFailure) {

        CompletableFuture<Void> futureFor(List<Long> successIds, List<OutboxEvent> failedEvents) {
            if (synchronousFailure != null) {
                recordFailure(failedEvents, synchronousFailure);
                return CompletableFuture.completedFuture(null);
            }
            return future
                    .thenAccept(result -> successIds.add(event.getId()))
                    .exceptionally(ex -> {
                        recordFailure(failedEvents, ex);
                        return null;
                    });
        }

        private void recordFailure(List<OutboxEvent> failedEvents, Throwable ex) {
            log.error("Failed to process OutboxEvent ID: {}", event.getId(), ex);
            event.recordFailure(ex.getMessage());
            event.unlock();
            failedEvents.add(event);
        }
    }
}
