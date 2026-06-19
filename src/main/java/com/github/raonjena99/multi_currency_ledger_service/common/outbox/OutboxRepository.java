package com.github.raonjena99.multi_currency_ledger_service.common.outbox;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface OutboxRepository extends JpaRepository<OutboxEvent, Long> {

    /**
     * 다중 인스턴스 환경에서 릴레이 워커 간의 데이터 경합 및 동시성 문제를 방지하기 위한 메서드
     * @param limit 한 번에 가져올 미처리 이벤트의 최대 청크 크기
     * @return 다른 워커가 선점하지 않은 미처리 이벤트 리스트
     */
    @Query(value = "SELECT * FROM outbox_events " +
                    "WHERE processed = false AND dead_letter = false " +
                    "ORDER BY created_at ASC " +
                    "LIMIT :limit " +
                    "FOR UPDATE SKIP LOCKED", 
            nativeQuery = true)
    List<OutboxEvent> findUnprocessedEventsWithSkipLocked(@Param("limit") int limit);
    
    List<OutboxEvent> findTop100ByProcessedFalseOrderByCreatedAtAsc();
}
