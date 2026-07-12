package com.github.raonjena99.multi_currency_ledger_service.common.outbox;

import java.util.List;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

/**
 * 데이터베이스에 저장된 OutboxEvent(아웃박스 이벤트)를 주기적으로 읽어와 메시지 브로커(Kafka)로 릴레이(Relay)하는 워커(Worker) 클래스입니다.
 * 폴링(Polling) 방식을 사용하여 미처리 이벤트를 처리합니다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxRelayWorker {

    private final OutboxRepository outboxRepository;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 지정된 주기(5초)마다 실행되며, 미처리된 아웃박스 이벤트를 조회하여 발행합니다.
     * SKIP LOCKED를 사용하여 다중 인스턴스 환경에서도 데이터 경합 없이 안전하게 이벤트를 가져옵니다.
     */
    @Scheduled(fixedDelay = 5000)
    @SchedulerLock(name = "outbox_relay_task", lockAtLeastFor = "PT2S", lockAtMostFor = "PT10S")
    @Transactional
    public void relayOutboxEvents() {
        // 미처리된 이벤트 중 최대 100건을 데이터베이스 락을 통해 선점하여 가져옴
        List<OutboxEvent> events = outboxRepository.findUnprocessedEventsWithSkipLocked(100);

        for (OutboxEvent event : events) {
            try {
                // Spring 내부 이벤트를 통해 Kafka 프로듀서 리스너로 전달하여 실제 발행 수행
                eventPublisher.publishEvent(new OutboxMessageEvent(event.getEventType(), event.getPayload()));
                // 발행이 성공하면 이벤트 상태를 처리됨(Processed)으로 변경
                event.markAsProcessed();
            } catch (Exception e) {
                // 발행 중 오류가 발생하면 실패 처리를 수행하고 다음 이벤트 처리로 넘어감
                log.error("Failed to process OutboxEvent ID: {}. Triggering Failure Logic.", event.getId(), e);
                event.recordFailure(e.getMessage());
            }
        }
    }
}