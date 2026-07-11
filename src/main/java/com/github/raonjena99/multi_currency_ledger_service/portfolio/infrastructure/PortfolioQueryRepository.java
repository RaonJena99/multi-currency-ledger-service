package com.github.raonjena99.multi_currency_ledger_service.portfolio.infrastructure;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.github.raonjena99.multi_currency_ledger_service.portfolio.domain.CurrentPortfolio;

/**
 * 포트폴리오 구체화된 뷰(Materialized View) 조회를 위한 PortfolioQueryRepository(포트폴리오 조회 저장소) 인터페이스입니다.
 */
public interface PortfolioQueryRepository extends JpaRepository<CurrentPortfolio, String> {
    
    /**
     * 특정 계좌 ID로 모든 자산 포트폴리오를 조회합니다.
     * @param accountId 계좌 ID
     * @return 해당 계좌의 CurrentPortfolio(현재 포트폴리오) 목록
     */
    List<CurrentPortfolio> findAllByAccountId(UUID accountId);
}
