package com.github.raonjena99.multi_currency_ledger_service.account.infrastructure;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.github.raonjena99.multi_currency_ledger_service.account.application.FxQuoteStore;
import com.github.raonjena99.multi_currency_ledger_service.account.domain.FxQuote;
import com.github.raonjena99.multi_currency_ledger_service.common.exception.FxQuoteUnavailableException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.json.JsonMapper;

/**
 * 환전 견적을 Redis 에 JSON 으로 저장하는 {@link FxQuoteStore} 구현체입니다.
 *
 * <p>견적은 유효 시간(TTL)이 지나면 Redis 가 지웁니다. 만료 시각을 코드로 따로 비교하지 않아도 되지만, 만료된
 * 견적과 처음부터 없던 견적은 구분되지 않습니다.
 *
 * <p><b>Redis 장애를 캐시 미스로 강등하지 않습니다.</b> 시세 캐시나 포트폴리오 캐시는 DB 로 폴백할 수 있지만,
 * 견적은 이 저장소가 유일한 보관처입니다. 견적을 내줬는데 저장하지 못했거나, 체결할 때 견적을 읽지 못했는데 "없는
 * 견적"으로 처리하면 고객은 잘못된 이유(만료)로 거절당합니다. 그래서 장애는 전용 예외로 올려 503 으로 알립니다.
 * 반대로 값이 깨져 읽을 수 없는 경우는 재시도해도 소용없으므로 없는 견적으로 봅니다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisFxQuoteStore implements FxQuoteStore {

    private static final String KEY_PREFIX = "ledger:fx-quote:";

    private final StringRedisTemplate redisTemplate;
    private final JsonMapper jsonMapper;

    @Override
    public void save(FxQuote quote, Duration ttl) {
        try {
            redisTemplate.opsForValue().set(key(quote.quoteId()), jsonMapper.writeValueAsString(quote), ttl);
        } catch (Exception e) {
            throw new FxQuoteUnavailableException("환전 견적을 저장하지 못했습니다: " + quote.quoteId(), e);
        }
    }

    @Override
    public Optional<FxQuote> find(UUID quoteId) {
        String raw;
        try {
            raw = redisTemplate.opsForValue().get(key(quoteId));
        } catch (Exception e) {
            throw new FxQuoteUnavailableException("환전 견적을 읽지 못했습니다: " + quoteId, e);
        }
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(jsonMapper.readValue(raw, FxQuote.class));
        } catch (Exception e) {
            log.error("읽을 수 없는 환전 견적입니다. 없는 견적으로 처리합니다. quoteId={}: {}", quoteId, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public void delete(UUID quoteId) {
        try {
            redisTemplate.delete(key(quoteId));
        } catch (Exception e) {
            throw new FxQuoteUnavailableException("환전 견적을 지우지 못했습니다: " + quoteId, e);
        }
    }

    private static String key(UUID quoteId) {
        return KEY_PREFIX + quoteId;
    }
}
