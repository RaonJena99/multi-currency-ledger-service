package com.github.raonjena99.multi_currency_ledger_service.account.infrastructure;

import java.util.UUID;

import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.github.raonjena99.multi_currency_ledger_service.account.domain.Account;

/**
 * Account(계좌) 엔티티에 대한 데이터 접근을 담당하는 Repository 인터페이스입니다.
 */
@Repository
public interface AccountRepository extends JpaRepository<Account, UUID> {

    /**
     * 계좌를 공유 잠금(FOR SHARE)으로 읽습니다. 거래·입출금이 계좌 상태를 확정할 때 씁니다.
     *
     * <p>공유 잠금끼리는 서로 막지 않으므로 같은 계좌의 거래는 동시에 진행됩니다. 상태 변경(배타 잠금)과만
     * 순서가 정해져, 해지·정지가 진행 중이면 그것이 끝난 뒤의 상태를 봅니다.
     */
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("SELECT a FROM Account a WHERE a.id = :id")
    Optional<Account> findByIdForShare(@Param("id") UUID id);

    /**
     * 계좌를 배타 잠금(FOR UPDATE)으로 읽습니다. 정지·해제·해지가 씁니다.
     * 진행 중인 거래가 커밋될 때까지 기다리므로, 해지는 그 거래의 잔고 변경까지 보고 판단합니다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM Account a WHERE a.id = :id")
    Optional<Account> findByIdForUpdate(@Param("id") UUID id);
}
