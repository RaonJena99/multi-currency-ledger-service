package com.github.raonjena99.multi_currency_ledger_service.reconciliation.infrastructure.adapter;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class PgSettlementAdapter {

    private final RestClient restClient;

    @CircuitBreaker(name = "pgSettlementApi")
    public ExternalSettlementDto fetchSettlement(String transactionId) {
        return restClient.get()
                .uri("/api/v1/pg/settlements/{id}", transactionId)
                .retrieve()
                .body(ExternalSettlementDto.class);
    }
}