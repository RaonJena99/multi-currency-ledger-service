package com.github.raonjena99.multi_currency_ledger_service.account.infrastructure;

import java.time.OffsetDateTime;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.github.raonjena99.multi_currency_ledger_service.account.domain.IdempotencyRecord;

public interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecord, String> {
    
    @Modifying
    @Query("DELETE FROM IdempotencyRecord i WHERE i.createdAt < :threshold")
    int deleteByCreatedAtBefore(@Param("threshold") OffsetDateTime threshold);
}
