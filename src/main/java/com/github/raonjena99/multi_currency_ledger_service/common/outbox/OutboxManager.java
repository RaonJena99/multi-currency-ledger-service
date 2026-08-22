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

        // 5분 동안 처리되지 않고 Lock 상태인 이벤트는 서버 다운 등으로 간주하고 강제로 다시 가져옴
        java.time.OffsetDateTime timeout = java.time.OffsetDateTime.now().minusMinutes(5);
        List<OutboxEvent> events = outboxRepository.findUnprocessedEventsWithSkipLocked(limit, timeout);
        for (OutboxEvent event : events) {
            event.lock();
        }
        return events;
    }

    /**
     * 비동기 처리가 끝난 이벤트들의 상태(성공, 실패, 잠금해제 등)를 벌크(일괄)로 저장합니다.
     * N+1 트랜잭션 오버헤드를 막기 위해 사용됩니다.
     * @param events 처리 결과가 반영된 이벤트 리스트
     */
    @Transactional
    public void updateEvents(List<OutboxEvent> events) {
        outboxRepository.saveAll(events);
    }
}
