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
}
