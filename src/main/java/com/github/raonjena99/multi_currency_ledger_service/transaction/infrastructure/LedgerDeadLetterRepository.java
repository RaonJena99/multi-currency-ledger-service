package com.github.raonjena99.multi_currency_ledger_service.transaction.infrastructure;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import com.github.raonjena99.multi_currency_ledger_service.transaction.domain.LedgerDeadLetter;

/**
 * 원장 기록 실패 격리 기록에 대한 저장소입니다.
 */
@Repository
public interface LedgerDeadLetterRepository extends JpaRepository<LedgerDeadLetter, Long> {

    /**
     * 아직 보상 처리되지 않은 원장 실패 건을 최신순으로 조회합니다.
     *
     * @param pageable 페이징 정보
     * @return 미해결 격리 기록 페이지
     */
    @Query("SELECT d FROM LedgerDeadLetter d WHERE d.isResolved = false ORDER BY d.createdAt DESC")
    Page<LedgerDeadLetter> findUnresolved(Pageable pageable);

    /**
     * 미해결 원장 실패 건수를 반환합니다. 알림·지표 노출에 사용합니다.
     *
     * @return 미해결 건수
     */
    @Query("SELECT COUNT(d) FROM LedgerDeadLetter d WHERE d.isResolved = false")
    long countUnresolved();
}
