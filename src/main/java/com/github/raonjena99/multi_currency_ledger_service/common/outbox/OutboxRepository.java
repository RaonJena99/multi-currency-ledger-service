package com.github.raonjena99.multi_currency_ledger_service.common.outbox;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * 데이터베이스에 저장된 OutboxEvent(아웃박스 이벤트) 엔티티에 접근하기 위한 리포지토리 인터페이스입니다.
 */
@Repository
public interface OutboxRepository extends JpaRepository<OutboxEvent, Long> {

    /**
     * 다중 인스턴스 환경에서 릴레이 워커 간의 데이터 경합 및 동시성 문제를 방지하기 위해 SKIP LOCKED를 사용하여 이벤트를 조회합니다.
     * 아직 처리되지 않았고 Dead Letter가 아닌 이벤트들을 생성 시간 순으로 가져옵니다.
     *
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
    
    /**
     * 미처리된 아웃박스 이벤트 중 오래된 순서로 최대 100건을 조회합니다.
     * (일반적인 단일 인스턴스 또는 단순 조회 용도)
     *
     * @return 미처리된 OutboxEvent 리스트
     */
    List<OutboxEvent> findTop100ByProcessedFalseOrderByCreatedAtAsc();
}
