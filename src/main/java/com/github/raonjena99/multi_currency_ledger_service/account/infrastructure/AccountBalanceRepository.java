package com.github.raonjena99.multi_currency_ledger_service.account.infrastructure;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.stereotype.Repository;

import com.github.raonjena99.multi_currency_ledger_service.account.domain.AccountBalance;

import jakarta.persistence.LockModeType;

@Repository
public interface AccountBalanceRepository extends JpaRepository<AccountBalance, Long> {
    
    // 비즈니스 로직용
    @Lock(LockModeType.OPTIMISTIC)
    Optional<AccountBalance> findByAccountIdAndAssetCode(UUID accountId, String assetCode);

    Optional<AccountBalance> readByAccountIdAndAssetCode(UUID accountId, String assetCode);
}