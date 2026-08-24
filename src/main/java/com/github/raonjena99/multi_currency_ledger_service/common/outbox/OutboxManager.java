package com.github.raonjena99.multi_currency_ledger_service.common.outbox;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class OutboxManager {
    private final OutboxRepository outboxRepository;

    /**
     * 조회된 미처리 이벤트를 잠금 처리
     * 
     * @param limit
     * @return
     */
    @Transactional
    public List<OutboxEvent> claimUnprocessedEvents(int limit) {

        // 5분 동안 처리되지 않고 Lock 상태인 이벤트는 서버 다운 등으로 간주하고 강제로 다시 가져옴
        java.time.OffsetDateTime now = java.time.OffsetDateTime.now();
        java.time.OffsetDateTime timeout = now.minusMinutes(5);
        List<OutboxEvent> events = outboxRepository.findUnprocessedEventsWithSkipLocked(limit, timeout, now);
        for (OutboxEvent event : events) {
            event.lock();
        }
        return events;
    }

    /**
     * 전송 성공한 이벤트는 1번의 벌크 쿼리로 처리하고,
     * 전송 실패한 이벤트는 기존처럼 개별 상태 갱신을 진행합니다.
     */
    @Transactional
    public void updateResults(List<Long> successIds, List<OutboxEvent> failedEvents) {
        // 성공 건 벌크 업데이트
        if (!successIds.isEmpty()) {
            outboxRepository.markAsProcessedInBatch(successIds);
        }

        // 실패 건 저장
        if (!failedEvents.isEmpty()) {
            outboxRepository.saveAll(failedEvents);
        }
    }

    /**
     * 데드레터로 격리된 이벤트 하나를 재발행 대상으로 되돌립니다.
     *
     * @param eventId 재적재할 이벤트 ID
     * @throws java.util.NoSuchElementException 해당 ID 의 이벤트가 없는 경우
     * @throws IllegalStateException 데드레터 상태가 아닌 경우
     */
    @Transactional
    public void requeueDeadLetter(Long eventId) {
        OutboxEvent event = outboxRepository.findById(eventId)
                .orElseThrow(() -> new java.util.NoSuchElementException("Outbox event not found: " + eventId));
        event.requeue();
    }

    /**
     * 데드레터로 격리된 이벤트를 오래된 순으로 최대 {@code limit} 건 재발행 대상으로 되돌립니다.
     *
     * @return 재적재된 이벤트 수
     */
    @Transactional
    public int requeueAllDeadLetters(int limit) {
        List<OutboxEvent> deadLetters = outboxRepository.findByDeadLetterTrueOrderByCreatedAtAsc(
                org.springframework.data.domain.PageRequest.of(0, limit));
        deadLetters.forEach(OutboxEvent::requeue);
        return deadLetters.size();
    }
}
