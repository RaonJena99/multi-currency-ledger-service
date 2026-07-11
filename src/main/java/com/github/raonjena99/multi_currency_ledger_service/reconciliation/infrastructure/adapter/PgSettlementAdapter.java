package com.github.raonjena99.multi_currency_ledger_service.reconciliation.infrastructure.adapter;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import io.micrometer.core.annotation.Timed;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 외부 PG(Payment Gateway) 시스템과 연동하여 정산 데이터를 조회하는 어댑터(Adapter) 클래스입니다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PgSettlementAdapter {

    private final RestClient restClient;
    private final MeterRegistry meterRegistry;

    /**
     * 외부 PG사 API를 호출하여 특정 거래 ID에 대한 대사 정보를 조회합니다.
     * 
     * @param transactionId 조회할 외부 거래 ID
     * @return 외부 정산 데이터 DTO (ExternalSettlementDto)
     */
    @Timed(value = "external.api.pg_settlement.response", description = "Time taken to fetch settlement from PG")
    @CircuitBreaker(name = "pgSettlementApi")
    @Retry(name = "pgSettlementApi")
    public ExternalSettlementDto fetchSettlement(String transactionId) {
        log.debug("외부 PG 정산망 데이터 Fetch 시도: {}", transactionId);
        
        return restClient.get()
                .uri("/api/v1/pg/settlements/{id}", transactionId)
                .retrieve()
                .body(ExternalSettlementDto.class);
    }

    /**
     * PG사 API 완전 다운 시의 우아한 성능 저하(Fallback) 처리
     */
    public ExternalSettlementDto fallbackSettlement(String transactionId, Throwable t) {
        log.error("[Fallback 작동] PG사 API 호출 완전 실패 (Circuit Open). 대상 ID: {}, 사유: {}", transactionId, t.getMessage());
        
        // 프로메테우스 지표 수집: 폴백 카운트 증가
        meterRegistry.counter("external.api.fallback.count", "api", "pgSettlement").increment();
        // 빈(Empty) 형태의 DTO를 반환하여 대사 매칭 실패(Dead Letter)로 자연스럽게 라우팅 유도
        return new ExternalSettlementDto(transactionId, null, null, null, "FALLBACK_STATUS", null);
    }
}