package com.github.raonjena99.multi_currency_ledger_service.common.outbox;

import java.util.List;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxRelayWorker {

    private final OutboxManager outboxManager;
    private final ApplicationEventPublisher eventPublisher;

    @Scheduled(fixedDelay = 5000)
    @SchedulerLock(name = "outbox_relay_task", lockAtLeastFor = "PT2S", lockAtMostFor = "PT10S")
    public void relayOutboxEvents() {
        List<OutboxEvent> events = outboxManager.claimUnprocessedEvents(100);
        
        if (events.isEmpty()) return;

        for (OutboxEvent event : events) {
            try {
                eventPublisher.publishEvent(new OutboxMessageEvent(event.getEventType(), event.getAggregateId(), event.getPayload(), event.getCorrelationId()));
                outboxManager.markAsProcessed(event.getId());
            } catch (Exception e) {
                log.error("Failed to process OutboxEvent ID: {}", event.getId(), e);
                outboxManager.recordFailure(event.getId(), e.getMessage());
            }
        }
    }
}