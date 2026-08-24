package com.github.raonjena99.multi_currency_ledger_service.common.infrastructure.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import com.github.raonjena99.multi_currency_ledger_service.common.exception.ArbitrageRiskException;
import com.github.raonjena99.multi_currency_ledger_service.common.exception.MarketDataUnavailableException;
import com.github.raonjena99.multi_currency_ledger_service.common.exception.UnsupportedAssetCodeException;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("시세 캐시: 신선도 판정과 역방향 동시 기록")
class ExchangeRateCacheTest {

    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOperations;

    private ExchangeRateCache cache;

    @BeforeEach
    void setUp() {
        cache = new ExchangeRateCache(redisTemplate);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    private static String withAge(String rate, Duration age) {
        return rate + "|" + Instant.now().minus(age).toEpochMilli();
    }

    @Test
    @DisplayName("허용 나이 이내면 신선한 값으로 반환한다")
    void freshCacheHit() {
        when(valueOperations.get("ledger:exchange-rate:BTC:KRW"))
                .thenReturn(withAge("106995446", Duration.ofMinutes(1)));

        var hit = cache.lookup("BTC", "KRW");

        assertThat(hit).isPresent();
        assertThat(hit.get().rate()).isEqualByComparingTo("106995446");
        assertThat(hit.get().isStale()).isFalse();
    }

    @Test
    @DisplayName("허용 나이를 넘기면 캐시 미스로 처리해 재조회를 유도한다")
    void expiredCacheForcesRefetch() {
        when(valueOperations.get(anyString())).thenReturn(withAge("100", Duration.ofMinutes(10)));

        assertThat(cache.lookup("USD", "KRW")).isEmpty();
    }

    @Test
    @DisplayName("5분 경계는 하드 리밋이다 - 5분 30초는 만료로 본다")
    void minuteTruncationDoesNotLeak() {
        // Duration.toMinutes() 절삭으로 비교하면 5분 59초까지 통과한다. 시간 단위로 비교해야 한다.
        when(valueOperations.get(anyString()))
                .thenReturn(withAge("100", Duration.ofMinutes(5).plusSeconds(30)));

        assertThat(cache.lookup("USD", "KRW")).isEmpty();
    }

    @Test
    @DisplayName("타임스탬프 없는 구버전 캐시는 나이를 알 수 없으므로 지연 데이터로 표시한다")
    void legacyCacheIsStale() {
        when(valueOperations.get(anyString())).thenReturn("1300");

        var hit = cache.lookup("USD", "KRW");

        assertThat(hit).isPresent();
        assertThat(hit.get().rate()).isEqualByComparingTo("1300");
        assertThat(hit.get().isStale()).isTrue();
    }

    @Test
    @DisplayName("Redis 장애는 예외를 올리지 않고 캐시 미스로 강등한다")
    void redisFailureDegradesToMiss() {
        when(valueOperations.get(anyString())).thenThrow(new RuntimeException("Redis down"));

        assertThat(cache.read("USD", "KRW")).isEmpty();
        assertThat(cache.lookup("USD", "KRW")).isEmpty();
    }

    @Test
    @DisplayName("조회한 시세는 역방향까지 함께 기록한다 - 공급자의 역방향 값보다 정밀하다")
    void writesReciprocalDirection() {
        cache.writeBothDirections("BTC", "KRW", new BigDecimal("106995446.66022733"));

        verify(valueOperations).set(eq("ledger:exchange-rate:BTC:KRW"), anyString(), any(Duration.class));
        verify(valueOperations).set(eq("ledger:exchange-rate:KRW:BTC"), anyString(), any(Duration.class));
    }

    @Test
    @DisplayName("0 이하 시세는 기록하지 않는다 - 역수 계산이 불가능하다")
    void doesNotWriteNonPositiveRate() {
        cache.writeBothDirections("BTC", "KRW", BigDecimal.ZERO);
        cache.writeBothDirections("BTC", "KRW", null);

        verify(valueOperations, org.mockito.Mockito.never())
                .set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    @DisplayName("캐시 쓰기 실패는 삼킨다 - 캐시는 거래 흐름을 막아서는 안 된다")
    void writeFailureIsSwallowed() {
        doThrow(new RuntimeException("Redis write down"))
                .when(valueOperations).set(anyString(), anyString(), any(Duration.class));

        cache.writeBothDirections("BTC", "KRW", new BigDecimal("100"));
    }

    @Test
    @DisplayName("열화: 영구 오류는 캐시를 보지 않고 그대로 전파한다")
    void degradeRethrowsPermanentFailure() {
        assertThatThrownBy(() -> cache.degradeOrThrow("AAPL", "USD",
                new UnsupportedAssetCodeException("지원하지 않는 코드")))
                .isInstanceOf(UnsupportedAssetCodeException.class);
    }

    @Test
    @DisplayName("열화: 만료된 캐시는 거래를 차단한다")
    void degradeBlocksOnStaleCache() {
        when(valueOperations.get(anyString())).thenReturn(withAge("3000000", Duration.ofMinutes(10)));

        assertThatThrownBy(() -> cache.degradeOrThrow("ETH", "KRW", new RuntimeException("API down")))
                .isInstanceOf(ArbitrageRiskException.class);
    }

    @Test
    @DisplayName("열화: 캐시까지 비어 있으면 시세 확보 불가로 503 을 유도한다")
    void degradeThrowsWhenCacheEmpty() {
        when(valueOperations.get(anyString())).thenReturn(null);

        assertThatThrownBy(() -> cache.degradeOrThrow("ETH", "KRW", new RuntimeException("API down")))
                .isInstanceOf(MarketDataUnavailableException.class);
    }

    @Test
    @DisplayName("열화: 신선한 캐시로 응답하되 지연 데이터로 표시한다")
    void degradeReturnsStaleFlaggedValue() {
        when(valueOperations.get(anyString())).thenReturn(withAge("3000000", Duration.ofMinutes(1)));

        var result = cache.degradeOrThrow("ETH", "KRW", new RuntimeException("API down"));

        assertThat(result.rate()).isEqualByComparingTo("3000000");
        assertThat(result.isStale()).isTrue();
    }

    @Test
    @DisplayName("readFresh 는 신선한 값만 통과시킨다")
    void readFreshFiltersByAge() {
        when(valueOperations.get("ledger:exchange-rate:USD:KRW"))
                .thenReturn(withAge("1384.72", Duration.ofSeconds(30)));
        assertThat(cache.readFresh("USD", "KRW")).contains(new BigDecimal("1384.72"));

        when(valueOperations.get("ledger:exchange-rate:USD:KRW"))
                .thenReturn(withAge("1384.72", Duration.ofHours(1)));
        assertThat(cache.readFresh("USD", "KRW")).isEmpty();
    }
}
