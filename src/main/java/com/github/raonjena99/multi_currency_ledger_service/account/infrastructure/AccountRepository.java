package com.github.raonjena99.multi_currency_ledger_service.account.infrastructure;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.github.raonjena99.multi_currency_ledger_service.account.domain.Account;

/**
 * Account(계좌) 엔티티에 대한 데이터 접근을 담당하는 Repository 인터페이스입니다.
 */
@Repository
public interface AccountRepository extends JpaRepository<Account, UUID> {
}
