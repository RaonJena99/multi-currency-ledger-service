package com.github.raonjena99.multi_currency_ledger_service.common.infrastructure.adapter;

import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.github.raonjena99.multi_currency_ledger_service.common.port.ExchangeRateProvider;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Primary
@Component
@RequiredArgsConstructor
public class LiveExchangeRateAdapter implements ExchangeRateProvider {

    private final RestClient restClient;
    // 운영 환경에서는 Redis 등 분산 캐시 사용을 권장합니다.
    private final Map<String, BigDecimal> lastKnownRates = new ConcurrentHashMap<>();

    @Override
    @Retry(name = "exchangeRateApi", fallbackMethod = "fallbackExchangeRate")
    @CircuitBreaker(name = "exchangeRateApi", fallbackMethod = "fallbackExchangeRate")
    public ExchangeRate getExchangeRate(String baseAsset, String targetAsset) {
        // [방어 로직] 기준 통화와 대상 통화가 같을 경우 네트워크 호출 방지 (O(1) 처리)
        if (baseAsset.equals(targetAsset)) {
            return new ExchangeRate(BigDecimal.ONE, false);
        }

        String cacheKey = baseAsset + "-" + targetAsset;

        BigDecimal rate = restClient.get()
                .uri("/api/v1/market-data/rates?base={base}&target={target}", baseAsset, targetAsset)
                .retrieve()
                .body(BigDecimal.class);

        if (rate != null) {
            lastKnownRates.put(cacheKey, rate); // 정상 상태 갱신
            return new ExchangeRate(rate, false);
        }
        return fallbackExchangeRate(baseAsset, targetAsset, new IllegalStateException("Empty API Response"));
    }

    /**
     * [최후 방어선 폴백]
     * 외부 API 통신 실패 시 상위 트랜잭션 붕괴를 막고, 직전 정상 데이터로 대체합니다.
     */
    public ExchangeRate fallbackExchangeRate(String baseAsset, String targetAsset, Throwable t) {
        String cacheKey = baseAsset + "-" + targetAsset;
        log.warn("🚨 [Fallback 작동] {}/{} 시세 조회 실패. 캐시 데이터로 우아한 성능 저하 시도. 사유: {}", 
                baseAsset, targetAsset, t.getMessage());
        
        // 캐시 데이터가 아예 없는 콜드 스타트(Cold Start) 상황 대비 기본값(1.0) 반환
        BigDecimal cachedRate = lastKnownRates.getOrDefault(cacheKey, BigDecimal.ONE);
        return new ExchangeRate(cachedRate, true); // 강제로 Stale 마커 주입
    }
}
