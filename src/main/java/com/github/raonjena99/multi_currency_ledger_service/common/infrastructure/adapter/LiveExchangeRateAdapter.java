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
import io.micrometer.core.annotation.Timed;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 실시간 외부 API를 통해 환율 정보를 제공하는 LiveExchangeRateAdapter(실시간 환율 어댑터) 클래스입니다.
 * Resilience4j를 사용하여 Circuit Breaker 및 Retry 패턴을 적용하고, Redis를 통한 분산 캐싱을 지원합니다.
 */
@Slf4j
@Primary
@Component
@RequiredArgsConstructor
public class LiveExchangeRateAdapter implements ExchangeRateProvider {

    private final RestClient restClient;
    private final StringRedisTemplate redisTemplate;

    private static final String CACHE_KEY_PREFIX = "ledger:exchange-rate:";
    private static final Duration CACHE_TTL = Duration.ofDays(1);

    /**
     * 외부 API를 호출하여 두 자산 간의 환율을 조회합니다.
     * API 호출 실패 시 Resilience4j의 설정에 따라 재시도(Retry)하며, 최종 실패 시 폴백(Fallback)을 실행합니다.
     *
     * @param baseAsset   기준 자산 코드
     * @param targetAsset 대상 자산 코드
     * @return 실시간 환율 정보를 담은 ExchangeRate(환율) 레코드
     */
    @Override
    @Timed(value = "external.api.exchange_rate.response", description = "Time taken to fetch exchange rate")
    @Retry(name = "exchangeRateApi", fallbackMethod = "fallbackExchangeRate")
    @CircuitBreaker(name = "exchangeRateApi", fallbackMethod = "fallbackExchangeRate")
    public ExchangeRate getExchangeRate(String baseAsset, String targetAsset) {
        if (baseAsset.equals(targetAsset)) {
            return new ExchangeRate(BigDecimal.ONE, false);
        }

        String cacheKey = CACHE_KEY_PREFIX + baseAsset + ":" + targetAsset;

        // 외부 API를 통해 실시간 시장 환율 데이터를 조회
        BigDecimal rate = restClient.get()
                .uri("/api/v1/market-data/rates?base={base}&target={target}", baseAsset, targetAsset)
                .retrieve()
                .body(BigDecimal.class);

        if (rate != null) {
            try {
                // API 응답 성공 시, 성능 향상 및 장애 대비를 위해 Redis에 환율 값을 캐싱 (TTL 적용)
                redisTemplate.opsForValue().set(cacheKey, rate.toPlainString(), CACHE_TTL);
            } catch (Exception e) {
                // Redis 캐시 저장 실패는 핵심 비즈니스 로직에 영향을 주지 않으므로 에러 로그만 남김
                log.error("Redis 분산 스토어 쓰기 실패 (인프라 글리치): {}", e.getMessage());
            }
            return new ExchangeRate(rate, false);
        }
        return fallbackExchangeRate(baseAsset, targetAsset, new IllegalStateException("Empty API Response"));
    }

    /**
     * 외부 API 호출 실패 시 동작하는 최후의 복원력 방어선(Fallback) 메서드입니다.
     * Redis 캐시에서 과거의 환율 데이터를 가져와 제한적인 성능 저하(Graceful Degradation) 모드로 동작합니다.
     *
     * @param baseAsset   기준 자산 코드
     * @param targetAsset 대상 자산 코드
     * @param t           발생한 예외 객체
     * @return 캐시된 환율 정보 또는 기본값을 담은 ExchangeRate(환율) 레코드 (isStale = true)
     */
    public ExchangeRate fallbackExchangeRate(String baseAsset, String targetAsset, Throwable t) {
        String cacheKey = CACHE_KEY_PREFIX + baseAsset + ":" + targetAsset;
        log.warn("[Fallback 작동] {}/{} 시세 조회 실패. Redis 분산 스토어 기반 성능 저하 시도. 사유: {}", 
                baseAsset, targetAsset, t.getMessage());
        
        try {
            // API 장애 시 Redis에 저장된 최신 캐시 데이터를 조회하여 시스템 마비를 방지함
            String cachedValue = redisTemplate.opsForValue().get(cacheKey);
            if (cachedValue != null) {
                return new ExchangeRate(new BigDecimal(cachedValue), true);
            }
        } catch (Exception e) {
            log.error("Redis 분산 스토어 읽기 실패 (Redis 클러스터 다운): {}", e.getMessage());
        }

        // 캐시 데이터마저 없을 경우, 최후의 수단으로 1:1 비율을 반환하여 시스템 에러를 방지
        return new ExchangeRate(BigDecimal.ONE, true);
    }
}
