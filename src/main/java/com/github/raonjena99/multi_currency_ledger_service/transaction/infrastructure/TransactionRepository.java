package com.github.raonjena99.multi_currency_ledger_service.transaction.infrastructure;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.github.raonjena99.multi_currency_ledger_service.transaction.domain.Transaction;

/**
 * 트랜잭션 애그리거트 루트 영속화 저장소
 */
@Repository
public interface TransactionRepository extends JpaRepository<Transaction, UUID> {
    
    @EntityGraph(attributePaths = {"entries"})
    Optional<Transaction> findWithEntriesById(UUID id);
}