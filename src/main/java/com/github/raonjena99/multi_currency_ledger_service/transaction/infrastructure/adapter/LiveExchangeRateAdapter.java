package com.github.raonjena99.multi_currency_ledger_service.transaction.infrastructure.adapter;

import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.github.raonjena99.multi_currency_ledger_service.transaction.application.port.ExchangeRateProvider;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Primary // 최우선 주입
@Component
@RequiredArgsConstructor
public class LiveExchangeRateAdapter implements ExchangeRateProvider {

    private final RestClient restClient;
    
    // 외부 인프라 장애 대비용 Last Known Good Data 캐시
    private final Map<String, BigDecimal> lastKnownRates = new ConcurrentHashMap<>();

    @Override
    // 재시도 로직 먼저 수행
    @Retry(name = "exchangeRateApi", fallbackMethod = "fallbackExchangeRate")
    // 실패율 초과 시 서킷 브레이커 개입
    @CircuitBreaker(name = "exchangeRateApi", fallbackMethod = "fallbackExchangeRate")
    public BigDecimal getExchangeRate(String baseAsset, String targetAsset) {
        String cacheKey = baseAsset + "-" + targetAsset;

        log.debug("외부 환율 API 호출 중... {}/{}", baseAsset, targetAsset);
        BigDecimal rate = restClient.get()
                .uri("/api/v1/market-data/rates?base={base}&target={target}", baseAsset, targetAsset)
                .retrieve()
                .body(BigDecimal.class);

        // 정상 응답 시 최신 환율 정보 갱신
        if (rate != null) {
            lastKnownRates.put(cacheKey, rate);
        }
        return rate;
    }

    /**
     * [Fallback Method] 
     * 원장 트랜잭션 롤백을 막기 위해 직전 캐시 데이터를 반환
     */
    public BigDecimal fallbackExchangeRate(String baseAsset, String targetAsset, Throwable t) {
        String cacheKey = baseAsset + "-" + targetAsset;
        log.warn("🚨 [Resilience 방어선 가동] 환율 API 장애 발생. 캐시된 직전 환율로 대체합니다. 자산: {}/{} | 사유: {}", 
                baseAsset, targetAsset, t.getMessage());

        // 캐시에도 데이터가 없을 경우
        return lastKnownRates.getOrDefault(cacheKey, BigDecimal.ONE);
    }
}
