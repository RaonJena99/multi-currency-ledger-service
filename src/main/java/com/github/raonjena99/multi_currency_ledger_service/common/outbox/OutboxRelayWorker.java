package com.github.raonjena99.multi_currency_ledger_service.common.outbox;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * 아웃박스의 미발행 이벤트를 Kafka 로 발행합니다.
 *
 * <p>한 번 실행에서 배치를 가져와 발행하고, 배치가 꽉 찼으면(= 더 남아 있을 가능성이 큼) 다음 주기를
 * 기다리지 않고 이어서 가져옵니다. 예전에는 5초마다 100 건만 가져와 처리량이 초당 약 20 건에 묶였고,
 * 부하 테스트에서 거래 API(초당 약 1,250 건)를 전혀 따라가지 못했습니다(docs/LOAD_TEST.md).
 *
 * <p>이어서 가져오기를 멈추는 경우:
 * <ul>
 *   <li>배치가 덜 찼다: 남은 이벤트가 없다.</li>
 *   <li>배치에 실패가 섞였다: 브로커 장애일 수 있다. 계속 가져오면 남은 이벤트 전부가 같은 실패를 겪으며
 *       실행만 길어지므로 다음 주기로 미룬다. 실패 건은 백오프 후 재시도된다.</li>
 *   <li>실행당 배치 상한에 닿았다: 스케줄러 스레드를 한 작업이 끝없이 붙잡지 않게 한다.</li>
 * </ul>
 */
@Slf4j
@Component
public class OutboxRelayWorker {

    private final OutboxManager outboxManager;
    private final OutboxMessageDispatcher messageDispatcher;
    private final int batchSize;
    private final int maxBatchesPerRun;

    public OutboxRelayWorker(OutboxManager outboxManager, OutboxMessageDispatcher messageDispatcher,
                             @Value("${ledger.outbox.relay.batch-size:500}") int batchSize,
                             @Value("${ledger.outbox.relay.max-batches-per-run:20}") int maxBatchesPerRun) {
        this.outboxManager = outboxManager;
        this.messageDispatcher = messageDispatcher;
        this.batchSize = batchSize;
        this.maxBatchesPerRun = maxBatchesPerRun;
    }

    // @SchedulerLock 을 걸지 않는다. 여러 노드가 동시에 돌아도 DB 의 SKIP LOCKED 가 같은 행을 나눠 갖지 않게 한다.
    @Scheduled(fixedDelayString = "${ledger.outbox.relay.poll-interval-ms:1000}")
    public void relayOutboxEvents() {
        for (int batch = 0; batch < maxBatchesPerRun; batch++) {
            if (!relayBatch() || Thread.currentThread().isInterrupted()) {
                return;
            }
        }
    }

    /**
     * 배치 하나를 가져와 발행합니다.
     *
     * @return 이어서 다음 배치를 가져와도 되면 true (배치가 꽉 찼고 실패가 없었다)
     */
    private boolean relayBatch() {
        List<OutboxEvent> events = outboxManager.claimUnprocessedEvents(batchSize);

        if (events.isEmpty())
            return false;

        // 성공한 ID와 실패한 이벤트를 분리해서 담을 스레드 안전한(Thread-Safe) 리스트
        List<Long> successIds = new CopyOnWriteArrayList<>();
        List<OutboxEvent> failedEvents = new CopyOnWriteArrayList<>();

        // 발송 준비와 대기를 모두 try 안에서 수행한다.
        //
        // KafkaTemplate.send 는 버퍼 고갈, 직렬화 실패, 메타데이터 타임아웃 등에서 동기 예외를
        // 던질 수 있다. 이 루프가 try 밖에 있으면 예외가 finally 를 건너뛰어 이미 성공한 이벤트의
        // successIds 가 버려지고(중복 발행), 선점된 이벤트가 실패 기록 없이 5분간 잠긴 채 남는다.
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
            return false;
        } finally {
            // 일괄(Bulk) 업데이트 처리
            outboxManager.updateResults(successIds, failedEvents);
        }

        return events.size() >= batchSize && failedEvents.isEmpty();
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
