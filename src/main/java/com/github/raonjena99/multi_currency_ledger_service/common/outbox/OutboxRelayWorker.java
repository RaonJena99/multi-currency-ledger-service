package com.github.raonjena99.multi_currency_ledger_service.common.outbox;

import java.util.List;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxRelayWorker {

    private final OutboxRepository outboxRepository;
    private final ApplicationEventPublisher eventPublisher;

    @Scheduled(fixedDelay = 5000)
    @Transactional
    public void relayOutboxEvents() {
        List<OutboxEvent> events = outboxRepository.findUnprocessedEventsWithSkipLocked(100);

        for (OutboxEvent event : events) {
            try {
                eventPublisher.publishEvent(new OutboxMessageEvent(event.getEventType(), event.getPayload()));
                event.markAsProcessed();
            } catch (Exception e) {
                log.error("Failed to process OutboxEvent ID: {}. Triggering Failure Logic.", event.getId(), e);
                event.recordFailure(e.getMessage());
            }
        }
    }
}