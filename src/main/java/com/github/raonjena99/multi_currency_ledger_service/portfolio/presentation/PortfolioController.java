package com.github.raonjena99.multi_currency_ledger_service.portfolio.presentation;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.github.raonjena99.multi_currency_ledger_service.portfolio.application.PortfolioQueryService;
import com.github.raonjena99.multi_currency_ledger_service.portfolio.application.dto.PortfolioSummaryResponse;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 포트폴리오 조회 API를 제공하는 PortfolioController(포트폴리오 컨트롤러) 클래스입니다.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/portfolios")
@RequiredArgsConstructor
public class PortfolioController {

    private final PortfolioQueryService portfolioQueryService;

    /**
     * CQRS Read Model 엔드포인트를 통해 특정 계좌의 포트폴리오 요약을 조회합니다.
     * @param accountId 조회할 계좌 ID
     * @return PortfolioSummaryResponse(포트폴리오 요약 응답) 객체를 포함한 ResponseEntity
     */
    @GetMapping("/{accountId}")
    public ResponseEntity<PortfolioSummaryResponse> getPortfolioSummary(@PathVariable UUID accountId) {
        log.debug("Fetching materialized portfolio summary for account: {}", accountId);
        
        PortfolioSummaryResponse response = portfolioQueryService.getPortfolioSummary(accountId);
        
        return ResponseEntity.ok(response);
    }
}
