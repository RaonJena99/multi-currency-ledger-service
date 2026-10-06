package com.github.raonjena99.multi_currency_ledger_service.portfolio.infrastructure.cache;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.serializer.GenericToStringSerializer;
import org.springframework.data.redis.serializer.RedisSerializer;
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

    /**
     * 계좌별 캐시 세대 키. 커밋 후 캐시를 지울 때마다 1 씩 오른다.
     *
     * <p>캐시보다 오래 남도록 TTL 을 길게 둔다. 먼저 만료되더라도 세대가 0 으로 돌아갈 뿐이고,
     * 그 전에 세대를 받아 둔 쪽은 값이 달라져 쓰지 않으므로 옛 잔고가 들어가지는 않는다.
     */
    private static final String GENERATION_KEY_PREFIX = "portfolio:generation:";
    private static final Duration GENERATION_TTL = Duration.ofDays(1);

    private static final RedisSerializer<Long> LONG_RESULT = new GenericToStringSerializer<>(Long.class);

    private static final DefaultRedisScript<Long> GENERATION_SCRIPT = new DefaultRedisScript<>(
            "return tonumber(redis.call('get', KEYS[1]) or '0')", Long.class);

    /** 세대가 받아 둔 값과 같을 때만 쓴다. KEYS = [캐시, 세대], ARGV = [받아 둔 세대, 값, TTL(초)] */
    private static final DefaultRedisScript<Long> SAVE_IF_GENERATION_SCRIPT = new DefaultRedisScript<>(
            "if (redis.call('get', KEYS[2]) or '0') ~= ARGV[1] then return 0 end "
                    + "redis.call('set', KEYS[1], ARGV[2], 'EX', ARGV[3]) "
                    + "return 1",
            Long.class);

    /** 세대를 올리고 캐시를 지운다. KEYS = [캐시, 세대], ARGV = [세대 TTL(초)] */
    private static final DefaultRedisScript<Long> EVICT_SCRIPT = new DefaultRedisScript<>(
            "redis.call('incr', KEYS[2]) "
                    + "redis.call('expire', KEYS[2], ARGV[1]) "
                    + "return redis.call('del', KEYS[1])",
            Long.class);

    @Override
    public Optional<PortfolioCacheDto> getPortfolioCache(UUID accountId) {
        String key = CACHE_KEY_PREFIX + accountId;
        PortfolioCacheDto cachedDto = (PortfolioCacheDto) redisTemplate.opsForValue().get(key);
        return Optional.ofNullable(cachedDto);
    }

    @Override
    public long currentGeneration(UUID accountId) {
        Long generation = redisTemplate.execute(GENERATION_SCRIPT, RedisSerializer.string(), LONG_RESULT,
                List.of(GENERATION_KEY_PREFIX + accountId));
        return generation != null ? generation : 0L;
    }

    @Override
    public boolean savePortfolioCacheIfGeneration(UUID accountId, PortfolioCacheDto dto, long expectedGeneration) {
        // 값은 조회 경로(opsForValue().get)가 읽을 수 있도록 템플릿의 값 직렬화기로 만든다.
        // 스크립트 인자는 세대 비교를 위해 문자열로 넘기므로, 직렬화한 JSON 을 UTF-8 문자열로 바꿔 전달한다.
        @SuppressWarnings("unchecked")
        RedisSerializer<Object> valueSerializer = (RedisSerializer<Object>) redisTemplate.getValueSerializer();
        String json = new String(valueSerializer.serialize(dto), StandardCharsets.UTF_8);

        Long saved = redisTemplate.execute(SAVE_IF_GENERATION_SCRIPT, RedisSerializer.string(), LONG_RESULT,
                List.of(CACHE_KEY_PREFIX + accountId, GENERATION_KEY_PREFIX + accountId),
                Long.toString(expectedGeneration), json, Long.toString(CACHE_DURATION.toSeconds()));
        return saved != null && saved == 1L;
    }

    @Override
    public void evictPortfolioCache(UUID accountId) {
        redisTemplate.execute(EVICT_SCRIPT, RedisSerializer.string(), LONG_RESULT,
                List.of(CACHE_KEY_PREFIX + accountId, GENERATION_KEY_PREFIX + accountId),
                Long.toString(GENERATION_TTL.toSeconds()));
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
