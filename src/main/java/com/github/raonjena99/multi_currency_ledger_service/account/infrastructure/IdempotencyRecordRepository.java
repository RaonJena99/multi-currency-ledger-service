package com.github.raonjena99.multi_currency_ledger_service.account.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;

import com.github.raonjena99.multi_currency_ledger_service.account.domain.IdempotencyRecord;

public interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecord, String> {
}
