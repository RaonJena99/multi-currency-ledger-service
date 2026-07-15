package com.github.raonjena99.multi_currency_ledger_service.common.outbox;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class OutboxManager {
    private final OutboxRepository outboxRepository;

    /**
     * 조회된 미처리 이벤트를 잠금 처리
     * @param limit
     * @return
     */
    @Transactional
    public List<OutboxEvent> claimUnprocessedEvents(int limit) {

        List<OutboxEvent> events = outboxRepository.findUnprocessedEventsWithSkipLocked(limit);
        for (OutboxEvent event : events) {
            event.lock();
        }
        return events;
    }

    /**
     * 이벤트를 처리 완료로 표시하고 잠금 해제
     * @param eventId
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markAsProcessed(Long eventId) {
        outboxRepository.findById(eventId).ifPresent(event -> {
            event.markAsProcessed();
            event.unlock();
        });
    }

    /**
     * 이벤트를 처리 실패로 표시하고 잠금 해제
     * @param eventId
     * @param errorMessage
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(Long eventId, String errorMessage) {
        outboxRepository.findById(eventId).ifPresent(event -> {
            event.recordFailure(errorMessage);
            event.unlock();
        });
    }
}
