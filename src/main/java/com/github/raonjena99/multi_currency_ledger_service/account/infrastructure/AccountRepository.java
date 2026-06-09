package com.github.raonjena99.multi_currency_ledger_service.account.infrastructure;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.github.raonjena99.multi_currency_ledger_service.account.domain.Account;

public interface AccountRepository extends JpaRepository<Account, UUID> {
}
