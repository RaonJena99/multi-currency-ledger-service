package com.github.raonjena99.multi_currency_ledger_service.reconciliation.infrastructure.adapter;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class PgSettlementAdapter {

    private final RestClient restClient;

    /**
     * 외부 PG사 대사 정보를 획득
     */
    @CircuitBreaker(name = "pgSettlementApi")
    @Retry(name = "pgSettlementApi")
    public ExternalSettlementDto fetchSettlement(String transactionId) {
        log.debug("외부 PG 정산망 데이터 Fetch 시도: {}", transactionId);
        
        return restClient.get()
                .uri("/api/v1/pg/settlements/{id}", transactionId)
                .retrieve()
                .body(ExternalSettlementDto.class);
    }
}