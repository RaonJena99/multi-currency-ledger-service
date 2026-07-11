package com.github.raonjena99.multi_currency_ledger_service.transaction.infrastructure;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.github.raonjena99.multi_currency_ledger_service.transaction.domain.Transaction;

/**
 * Transaction(트랜잭션) 애그리거트 루트 영속화 저장소
 */
@Repository
public interface TransactionRepository extends JpaRepository<Transaction, UUID> {
    
    /**
     * ID를 기반으로 Transaction(트랜잭션)과 연관된 엔트리들을 함께 조회합니다.
     * @param id 조회할 트랜잭션의 UUID
     * @return 조회된 트랜잭션의 Optional 객체
     */
    @EntityGraph(attributePaths = {"entries"})
    Optional<Transaction> findWithEntriesById(UUID id);
}