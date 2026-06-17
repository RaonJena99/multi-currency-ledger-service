package com.github.raonjena99.multi_currency_ledger_service.reconciliation;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import com.github.raonjena99.multi_currency_ledger_service.reconciliation.domain.ReconciliationDeadLetter;

@Repository
public interface ReconciliationDeadLetterRepository extends JpaRepository<ReconciliationDeadLetter, Long> {

    /**
     * 아직 관리자가 수동 조정하지 않은(미결) 데드 레터 목록을 페이징 조회
     */
    @Query("SELECT d FROM ReconciliationDeadLetter d WHERE d.isResolved = false ORDER BY d.createdAt DESC")
    Page<ReconciliationDeadLetter> findUnresolvedDeadLetters(Pageable pageable);
}
