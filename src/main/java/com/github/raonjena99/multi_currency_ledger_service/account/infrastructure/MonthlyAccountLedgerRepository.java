package com.github.raonjena99.multi_currency_ledger_service.account.infrastructure;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.stereotype.Repository;

import com.github.raonjena99.multi_currency_ledger_service.account.domain.MonthlyAccountLedger;

import jakarta.persistence.LockModeType;

@Repository
public interface MonthlyAccountLedgerRepository extends JpaRepository<MonthlyAccountLedger, Long> {
    
    // 비즈니스 로직용
    @Lock(LockModeType.OPTIMISTIC)
    Optional<MonthlyAccountLedger> findByAccountIdAndAssetCodeAndLedgerMonth(
        UUID accountId, String assetCode, String ledgerMonth
    );

    // 이월 처리를 위한 과거 내역 조회
    Optional<MonthlyAccountLedger> findFirstByAccountIdAndAssetCodeOrderByLedgerMonthDesc(
        UUID accountId, String assetCode
    );
}