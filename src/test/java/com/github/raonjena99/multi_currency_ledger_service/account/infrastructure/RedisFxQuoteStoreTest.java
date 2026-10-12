package com.github.raonjena99.multi_currency_ledger_service.account.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import com.github.raonjena99.multi_currency_ledger_service.IntegrationTestSupport;
import com.github.raonjena99.multi_currency_ledger_service.account.application.FxQuoteStore;
import com.github.raonjena99.multi_currency_ledger_service.account.domain.FxDirection;
import com.github.raonjena99.multi_currency_ledger_service.account.domain.FxQuote;
import com.github.raonjena99.multi_currency_ledger_service.common.exception.FxQuoteUnavailableException;

import tools.jackson.databind.json.JsonMapper;

/**
 * 환전 견적을 실제 Redis 에 저장하고 읽는 어댑터를 검증합니다.
 *
 * <p>견적은 금액과 환율의 정밀도가 그대로 보존되어야 하고, 유효 시간이 지나면 저장소가 지워야 합니다.
 * 저장소 장애는 500 이 아니라 503 으로 이어지는 전용 예외여야 합니다.
 */
@DisplayName("환전 견적 저장소 (Redis)")
class RedisFxQuoteStoreTest extends IntegrationTestSupport {

    @Autowired private FxQuoteStore store;
    @Autowired private StringRedisTemplate redisTemplate;
    @Autowired private JsonMapper jsonMapper;

    private static FxQuote quote() {
        OffsetDateTime now = OffsetDateTime.now();
        return new FxQuote(UUID.randomUUID(), UUID.randomUUID(), FxDirection.BUY_FOREIGN,
                "KRW", new BigDecimal("1300000"), "USD", new BigDecimal("998.25"),
                new BigDecimal("1300.123456789012345678"), new BigDecimal("1302.275000000000000000"),
                new BigDecimal("0.0175"), new BigDecimal("0.9"), now, now.plusSeconds(30));
    }

    @Test
    @DisplayName("저장한 견적을 모든 필드와 정밀도 그대로 읽는다")
    void roundTripsEveryField() {
        FxQuote saved = quote();

        store.save(saved, Duration.ofSeconds(30));
        Optional<FxQuote> found = store.find(saved.quoteId());

        assertThat(found).isPresent();
        assertThat(found.get()).usingRecursiveComparison()
                .withComparatorForType(BigDecimal::compareTo, BigDecimal.class)
                .withComparatorForType((a, b) -> a.toInstant().compareTo(b.toInstant()), OffsetDateTime.class)
                .isEqualTo(saved);
        // 소수 18자리 시세가 부동소수점으로 깎이지 않았는지 따로 확인한다.
        assertThat(found.get().marketRate()).isEqualByComparingTo("1300.123456789012345678");
    }

    @Test
    @DisplayName("없는 견적은 비어 있다")
    void unknownQuoteIsEmpty() {
        assertThat(store.find(UUID.randomUUID())).isEmpty();
    }

    @Test
    @DisplayName("유효 시간이 지나면 저장소가 견적을 지운다")
    void quoteExpiresAfterItsTtl() {
        FxQuote saved = quote();

        store.save(saved, Duration.ofSeconds(1));
        assertThat(store.find(saved.quoteId())).isPresent();

        await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> assertThat(store.find(saved.quoteId())).isEmpty());
    }

    @Test
    @DisplayName("저장된 견적에는 유효 시간(TTL)이 걸려 있다")
    void storedQuoteHasATtl() {
        FxQuote saved = quote();

        store.save(saved, Duration.ofSeconds(30));

        Long ttl = redisTemplate.getExpire("ledger:fx-quote:" + saved.quoteId());
        assertThat(ttl).isBetween(1L, 30L);
    }

    @Test
    @DisplayName("읽을 수 없는 값은 없는 견적으로 본다 (예외로 요청을 깨뜨리지 않는다)")
    void corruptedValueIsTreatedAsMissing() {
        UUID id = UUID.randomUUID();
        redisTemplate.opsForValue().set("ledger:fx-quote:" + id, "{ not json", Duration.ofSeconds(30));

        assertThat(store.find(id)).isEmpty();
    }

    @Test
    @DisplayName("Redis 를 쓸 수 없으면 저장도 조회도 전용 예외(503)로 알린다")
    @SuppressWarnings("unchecked")
    void redisFailureIsReportedAsUnavailable() {
        StringRedisTemplate broken = mock(StringRedisTemplate.class);
        ValueOperations<String, String> operations = mock(ValueOperations.class);
        when(broken.opsForValue()).thenReturn(operations);
        org.mockito.Mockito.doThrow(new RedisConnectionFailureException("down"))
                .when(operations).set(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any(Duration.class));
        when(operations.get(org.mockito.ArgumentMatchers.anyString()))
                .thenThrow(new RedisConnectionFailureException("down"));
        RedisFxQuoteStore failing = new RedisFxQuoteStore(broken, jsonMapper);

        assertThatThrownBy(() -> failing.save(quote(), Duration.ofSeconds(30)))
                .isInstanceOf(FxQuoteUnavailableException.class);
        assertThatThrownBy(() -> failing.find(UUID.randomUUID()))
                .isInstanceOf(FxQuoteUnavailableException.class);
    }
}
