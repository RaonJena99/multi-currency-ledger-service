package com.github.raonjena99.multi_currency_ledger_service.portfolio.infrastructure;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.github.raonjena99.multi_currency_ledger_service.portfolio.domain.CurrentPortfolio;

public interface PortfolioQueryRepository extends JpaRepository<CurrentPortfolio, String> {
    List<CurrentPortfolio> findAllByAccountId(UUID accountId);
}
