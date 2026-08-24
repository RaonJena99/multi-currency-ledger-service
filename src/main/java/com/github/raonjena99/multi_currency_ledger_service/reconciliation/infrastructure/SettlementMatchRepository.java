package com.github.raonjena99.multi_currency_ledger_service.reconciliation.infrastructure;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.github.raonjena99.multi_currency_ledger_service.reconciliation.domain.SettlementMatch;

/**
 * 정산 ↔ 내부 거래 매칭 관계 저장소입니다.
 */
@Repository
public interface SettlementMatchRepository extends JpaRepository<SettlementMatch, UUID> {

    /**
     * 특정 정산 건에 이미 기록된 매칭 행을 조회합니다.
     *
     * <p>청크 롤백에서 살아남은(REQUIRES_NEW) 고아 매칭 행을 재실행 시 식별하는 용도입니다.
     * {@code uk_settlement_match_settlement} 유니크 제약과 같은 컬럼 조합을 사용합니다.
     */
    java.util.Optional<SettlementMatch> findByExternalSettlementIdAndSettlementDate(
            UUID externalSettlementId, java.time.OffsetDateTime settlementDate);
}
