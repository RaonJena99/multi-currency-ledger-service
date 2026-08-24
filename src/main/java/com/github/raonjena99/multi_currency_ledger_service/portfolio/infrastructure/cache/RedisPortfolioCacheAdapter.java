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

    /**
     * 락 키별 소유 토큰. 단일 토큰을 쓰면 한 스레드가 서로 다른 키를 연달아 잠글 때
     * 앞의 토큰이 덮어써져 자기 락을 해제하지 못한다.
     */
    private final ThreadLocal<java.util.Map<String, String>> lockTokens = ThreadLocal.withInitial(java.util.HashMap::new);

    private static final org.springframework.data.redis.core.script.DefaultRedisScript<Long> RELEASE_SCRIPT =
            new org.springframework.data.redis.core.script.DefaultRedisScript<>(
                    "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
                    Long.class);

    @Override
    public boolean tryAcquireLock(String lockKey, long timeoutSeconds) {
        String token = UUID.randomUUID().toString();
        Boolean locked = redisTemplate.opsForValue().setIfAbsent(lockKey, token, Duration.ofSeconds(timeoutSeconds));
        if (Boolean.TRUE.equals(locked)) {
            lockTokens.get().put(lockKey, token);
            return true;
        }
        return false;
    }

    @Override
    public void releaseLock(String lockKey) {
        java.util.Map<String, String> tokens = lockTokens.get();
        String token = tokens.remove(lockKey);
        if (token == null) {
            return;
        }
        try {
            // 토큰이 일치할 때만 삭제한다. TTL 만료 후 다른 소유자가 잡은 락을 지우지 않기 위함이다.
            redisTemplate.execute(RELEASE_SCRIPT, java.util.Collections.singletonList(lockKey), token);
        } finally {
            if (tokens.isEmpty()) {
                lockTokens.remove();
            }
        }
    }
}
