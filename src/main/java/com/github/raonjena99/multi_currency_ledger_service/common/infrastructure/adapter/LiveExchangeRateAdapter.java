package com.github.raonjena99.multi_currency_ledger_service.common.infrastructure.adapter;

import java.math.BigDecimal;
import java.time.Duration;

import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
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
    private final StringRedisTemplate redisTemplate;

    private static final String CACHE_KEY_PREFIX = "ledger:exchange-rate:";
    private static final Duration CACHE_TTL = Duration.ofDays(1);


    @Override
    @Retry(name = "exchangeRateApi", fallbackMethod = "fallbackExchangeRate")
    @CircuitBreaker(name = "exchangeRateApi", fallbackMethod = "fallbackExchangeRate")
    public ExchangeRate getExchangeRate(String baseAsset, String targetAsset) {
        if (baseAsset.equals(targetAsset)) {
            return new ExchangeRate(BigDecimal.ONE, false);
        }

        String cacheKey = CACHE_KEY_PREFIX + baseAsset + ":" + targetAsset;

        BigDecimal rate = restClient.get()
                .uri("/api/v1/market-data/rates?base={base}&target={target}", baseAsset, targetAsset)
                .retrieve()
                .body(BigDecimal.class);

        if (rate != null) {
            try {
                redisTemplate.opsForValue().set(cacheKey, rate.toPlainString(), CACHE_TTL);
            } catch (Exception e) {
                log.error("Redis 분산 스토어 쓰기 실패 (인프라 글리치): {}", e.getMessage());
            }
            return new ExchangeRate(rate, false);
        }
        return fallbackExchangeRate(baseAsset, targetAsset, new IllegalStateException("Empty API Response"));
    }

    /**
     * 최후 복원력 방어선 폴백
     */
    public ExchangeRate fallbackExchangeRate(String baseAsset, String targetAsset, Throwable t) {
        String cacheKey = CACHE_KEY_PREFIX + baseAsset + ":" + targetAsset;
        log.warn("[Fallback 작동] {}/{} 시세 조회 실패. Redis 분산 스토어 기반 성능 저하 시도. 사유: {}", 
                baseAsset, targetAsset, t.getMessage());
        
        try {
            String cachedValue = redisTemplate.opsForValue().get(cacheKey);
            if (cachedValue != null) {
                return new ExchangeRate(new BigDecimal(cachedValue), true);
            }
        } catch (Exception e) {
            log.error("Redis 분산 스토어 읽기 실패 (Redis 클러스터 다운): {}", e.getMessage());
        }

        return new ExchangeRate(BigDecimal.ONE, true);
    }
}
