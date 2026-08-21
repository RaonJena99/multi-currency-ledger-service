package com.github.raonjena99.multi_currency_ledger_service.portfolio.infrastructure.cache;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import com.github.raonjena99.multi_currency_ledger_service.portfolio.application.dto.PortfolioCacheDto;
import com.github.raonjena99.multi_currency_ledger_service.portfolio.application.port.PortfolioCachePort;

import lombok.RequiredArgsConstructor;

/**
 * PortfolioCachePort의 구현체로, Redis를 사용하여 캐시 인프라를 담당합니다.
 */
@Component
@RequiredArgsConstructor
public class RedisPortfolioCacheAdapter implements PortfolioCachePort {

    private final RedisTemplate<String, Object> redisTemplate;
    
    private static final String CACHE_KEY_PREFIX = "portfolio:account:";
    private static final Duration CACHE_DURATION = Duration.ofHours(1);

    @Override
    public Optional<PortfolioCacheDto> getPortfolioCache(UUID accountId) {
        String key = CACHE_KEY_PREFIX + accountId;
        PortfolioCacheDto cachedDto = (PortfolioCacheDto) redisTemplate.opsForValue().get(key);
        return Optional.ofNullable(cachedDto);
    }

    @Override
    public void savePortfolioCache(UUID accountId, PortfolioCacheDto dto) {
        String key = CACHE_KEY_PREFIX + accountId;
        redisTemplate.opsForValue().set(key, dto, CACHE_DURATION);
    }

    @Override
    public void evictPortfolioCache(UUID accountId) {
        String key = CACHE_KEY_PREFIX + accountId;
        redisTemplate.delete(key);
    }

    @Override
    public boolean tryAcquireLock(String lockKey, long timeoutSeconds) {
        Boolean locked = redisTemplate.opsForValue().setIfAbsent(lockKey, "LOCKED", Duration.ofSeconds(timeoutSeconds));
        return Boolean.TRUE.equals(locked);
    }

    @Override
    public void releaseLock(String lockKey) {
        redisTemplate.delete(lockKey);
    }
}
