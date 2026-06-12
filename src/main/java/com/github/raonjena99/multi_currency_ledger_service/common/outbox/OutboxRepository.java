package com.github.raonjena99.multi_currency_ledger_service.common.outbox;

import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.stereotype.Repository;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;

@Repository
public interface OutboxRepository extends JpaRepository<OutboxEvent, Long> {

    /**
     * 다중 릴레이 워커 분산 환경용 미처리 이벤트 폴링
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints({
        @QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2")
    })
    @Query("SELECT o FROM OutboxEvent o WHERE o.processed = false ORDER BY o.createdAt ASC")
    List<OutboxEvent> findUnprocessedEvents(Pageable pageable);
    
    List<OutboxEvent> findTop100ByProcessedFalseOrderByCreatedAtAsc();
}
