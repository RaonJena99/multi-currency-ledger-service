package com.github.raonjena99.multi_currency_ledger_service.common.outbox;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface OutboxRepository extends JpaRepository<OutboxEvent, Long> {

    @Query(value = "SELECT * FROM outbox_events " +
                    "WHERE processed = false AND dead_letter = false " +
                    "ORDER BY created_at ASC " +
                    "LIMIT :limit " +
                    "FOR UPDATE SKIP LOCKED", 
            nativeQuery = true)
    List<OutboxEvent> findUnprocessedEventsWithSkipLocked(@Param("limit") int limit);
    
    List<OutboxEvent> findTop100ByProcessedFalseOrderByCreatedAtAsc();
}
