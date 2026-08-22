package com.github.raonjena99.multi_currency_ledger_service.account.infrastructure;

import java.time.OffsetDateTime;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import com.github.raonjena99.multi_currency_ledger_service.account.domain.IdempotencyRecord;

public interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecord, String> {
    
    // 대량 삭제로 인한 Lock Escalation 및 트랜잭션 로그 스파이크를 방지하기 위해 청크(Chunk) 단위로 삭제합니다.
    @Transactional
    @Modifying
    @Query(nativeQuery = true, value = "DELETE FROM idempotency_records WHERE created_at < :threshold LIMIT :limit")
    int deleteByCreatedAtBeforeWithLimit(@Param("threshold") OffsetDateTime threshold, @Param("limit") int limit);
}
