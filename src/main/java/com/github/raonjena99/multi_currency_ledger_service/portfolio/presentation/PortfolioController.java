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

@Slf4j
@RestController
@RequestMapping("/api/v1/portfolios")
@RequiredArgsConstructor
public class PortfolioController {

    private final PortfolioQueryService portfolioQueryService;

    /**
     * CQRS Read Model 엔드포인트
     */
    @GetMapping("/{accountId}")
    public ResponseEntity<PortfolioSummaryResponse> getPortfolioSummary(@PathVariable UUID accountId) {
        log.debug("Fetching materialized portfolio summary for account: {}", accountId);
        
        PortfolioSummaryResponse response = portfolioQueryService.getPortfolioSummary(accountId);
        
        return ResponseEntity.ok(response);
    }
}
