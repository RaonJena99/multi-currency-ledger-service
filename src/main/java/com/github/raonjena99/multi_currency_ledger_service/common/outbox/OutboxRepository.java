package com.github.raonjena99.multi_currency_ledger_service.common.outbox;

import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * 데이터베이스에 저장된 OutboxEvent(아웃박스 이벤트) 엔티티에 접근하기 위한 리포지토리 인터페이스입니다.
 */
@Repository
public interface OutboxRepository extends JpaRepository<OutboxEvent, Long> {

    /**
     * 아직 처리되지 않았고 Dead Letter가 아니며 백오프 대기가 끝난 이벤트들을 생성 시간 순으로 가져옵니다.
     *
     * <p>{@code next_attempt_at} 필터가 없으면 실패한 이벤트가 폴링 주기(5초)마다 즉시 재시도되어,
     * 브로커가 몇 분만 다운되어도 재시도 예산이 소진됩니다.
     *
     * @param limit 한 번에 가져올 미처리 이벤트의 최대 청크 크기
     * @param timeout 이 시각보다 오래 잠긴 이벤트는 워커 다운으로 간주하고 다시 가져옵니다
     * @param now 현재 시각. 백오프 대기(next_attempt_at)가 끝났는지 판정합니다
     * @return 다른 워커가 선점하지 않은 미처리 이벤트 리스트
     */
    @Query(value = "SELECT * FROM outbox_events " +
            "WHERE processed = false AND dead_letter = false " +
            "AND (locked_at IS NULL OR locked_at < :timeout) " +
            "AND (next_attempt_at IS NULL OR next_attempt_at <= :now) " +
            "ORDER BY created_at ASC " +
            "LIMIT :limit " +
            "FOR UPDATE SKIP LOCKED", nativeQuery = true)
    List<OutboxEvent> findUnprocessedEventsWithSkipLocked(@Param("limit") int limit,
            @Param("timeout") OffsetDateTime timeout,
            @Param("now") OffsetDateTime now);

    @Modifying(clearAutomatically = true)
    @Query("UPDATE OutboxEvent e " +
            "SET e.processed = true, e.lockedAt = null " +
            "WHERE e.id IN :ids")
    void markAsProcessedInBatch(@Param("ids") List<Long> successIds);

    /**
     * 데드레터로 격리된 이벤트를 오래된 순으로 조회합니다. 백오피스 복구 화면용입니다.
     */
    List<OutboxEvent> findByDeadLetterTrueOrderByCreatedAtAsc(org.springframework.data.domain.Pageable pageable);

    long countByDeadLetterTrue();
}
