package com.github.raonjena99.multi_currency_ledger_service.common.infrastructure.adapter;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.github.raonjena99.multi_currency_ledger_service.common.exception.ArbitrageRiskException;
import com.github.raonjena99.multi_currency_ledger_service.common.exception.MarketDataUnavailableException;
import com.github.raonjena99.multi_currency_ledger_service.common.exception.UnsupportedAssetCodeException;
import com.github.raonjena99.multi_currency_ledger_service.common.port.ExchangeRateProvider.ExchangeRate;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 시세 캐시를 Redis 에 읽고 쓰는 공용 컴포넌트입니다. 모든 시세 어댑터가 이 캐시를 공유합니다.
 *
 * <p>공급자별로 캐시 로직을 복제하면 신선도 기준과 키 규칙이 갈라집니다. 실제로 그렇게 되면
 * 단건 조회는 만료로 거래를 차단하는데 배치 조회는 같은 낡은 값을 정상 데이터로 표시하는,
 * 눈에 잘 안 보이는 불일치가 생깁니다. 그래서 한 곳에 모았습니다.
 *
 * <p><b>역방향 동시 기록이 이 클래스의 핵심 기능입니다.</b> 무료 시세 공급자는 환율을 절대
 * 소수 자릿수로 양자화하므로, 1 보다 훨씬 작은 환율은 유효숫자가 통째로 날아갑니다.
 * (fxratesapi 실측: {@code KRW→BTC} 참값 {@code 9.3461e-9} 를 {@code 9e-9} 로 반환, 오차 3.7%.
 * {@code places} 파라미터로도 복구되지 않습니다.) 값이 큰 방향을 한 번 조회해 역수를
 * {@code BigDecimal} 로 계산해 두면 공급자의 역방향 값보다 정밀하고, 분당 호출 쿼터도 절약됩니다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExchangeRateCache {

    private static final String KEY_PREFIX = "ledger:exchange-rate:";

    /** 캐시 TTL. 만료 나이({@link #MAX_AGE})와 같게 두어 만료된 값이 남지 않게 합니다. */
    private static final Duration TTL = Duration.ofMinutes(5);

    /**
     * 캐시된 시세를 신선하다고 인정하는 최대 나이.
     *
     * <p>{@code Duration.toMinutes()} 는 절삭이므로 {@code toMinutes() <= 5} 로 비교하면
     * 실제로는 5분 59초까지 통과합니다. "5분 하드 리밋"을 지키려면 시간 단위로 비교해야 합니다.
     */
    public static final Duration MAX_AGE = Duration.ofMinutes(5);

    /** 역수 계산 스케일. {@code numeric(36,18)} 컬럼과 {@code Money} 내부 정밀도에 맞춥니다. */
    public static final int RATE_SCALE = 18;

    private final StringRedisTemplate redisTemplate;

    /**
     * 캐시 키를 만듭니다. {@code base:quote} 는 "1 base 의 quote 표시 가치"를 뜻합니다.
     *
     * @param base  기준 자산 코드
     * @param quote 표시 통화 코드
     * @return Redis 키
     */
    public String key(String base, String quote) {
        return KEY_PREFIX + base + ":" + quote;
    }

    /**
     * 캐시를 조회합니다. Redis 장애는 예외를 올리지 않고 캐시 미스로 강등합니다.
     * 그러지 않으면 Redis 와 함께 조회 API 가 죽습니다.
     *
     * @param base  기준 자산 코드
     * @param quote 표시 통화 코드
     * @return 캐시된 시세. 없거나 Redis 장애면 비어 있음
     */
    public Optional<CachedRate> read(String base, String quote) {
        try {
            String raw = redisTemplate.opsForValue().get(key(base, quote));
            if (raw == null || raw.isBlank()) {
                return Optional.empty();
            }

            int separator = raw.indexOf('|');
            if (separator < 0) {
                // 타임스탬프가 없는 구버전 캐시는 나이를 알 수 없으므로 지연 데이터로 취급한다.
                return Optional.of(new CachedRate(new BigDecimal(raw), null));
            }

            BigDecimal rate = new BigDecimal(raw.substring(0, separator));
            Instant cachedAt = Instant.ofEpochMilli(Long.parseLong(raw.substring(separator + 1)));
            return Optional.of(new CachedRate(rate, cachedAt));
        } catch (Exception e) {
            log.warn("시세 캐시 읽기 실패. 캐시 미스로 처리합니다. {}/{}: {}", base, quote, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 지금 그대로 사용해도 되는(신선한) 시세만 반환합니다.
     *
     * @param base  기준 자산 코드
     * @param quote 표시 통화 코드
     * @return 허용 나이 이내의 시세. 없으면 비어 있음
     */
    public Optional<BigDecimal> readFresh(String base, String quote) {
        return read(base, quote).filter(CachedRate::isFresh).map(CachedRate::rate);
    }

    /**
     * 조회한 시세를 정방향과 역방향에 함께 기록합니다.
     *
     * <p>쓰기 실패는 삼킵니다. 캐시는 성능·복원력 보조 수단이므로 핵심 거래 흐름을 막아서는
     * 안 됩니다.
     *
     * @param base  기준 자산 코드
     * @param quote 표시 통화 코드
     * @param rate  1 base 의 quote 표시 가치
     */
    public void writeBothDirections(String base, String quote, BigDecimal rate) {
        if (rate == null || rate.signum() <= 0) {
            return;
        }

        long now = Instant.now().toEpochMilli();
        write(key(base, quote), rate, now);
        write(key(quote, base), BigDecimal.ONE.divide(rate, RATE_SCALE, RoundingMode.HALF_EVEN), now);
    }

    /**
     * 캐시만으로 응답할 수 있는지 판단합니다. 공급자를 호출하기 전에 이 메서드로 빠른 경로를
     * 확인합니다.
     *
     * <p>이 판정을 어댑터마다 복제하면 신선도 정책이 갈라집니다. 실제로 그렇게 되면 단건 조회는
     * 만료로 거래를 차단하는데 배치 조회는 같은 낡은 값을 {@code isStale=false} 로 표시해
     * 정상 데이터처럼 보여주는 불일치가 생깁니다.
     *
     * @param base  기준 자산 코드
     * @param quote 표시 통화 코드
     * @return 그대로 반환해도 되는 시세. 공급자 재조회가 필요하면 비어 있음
     */
    public Optional<ExchangeRate> lookup(String base, String quote) {
        var cached = read(base, quote);
        if (cached.isEmpty()) {
            return Optional.empty();
        }
        if (cached.get().isFresh()) {
            return Optional.of(new ExchangeRate(cached.get().rate(), false));
        }
        if (cached.get().cachedAt() == null) {
            // 타임스탬프 없는 구버전 캐시는 나이를 알 수 없으므로 지연 데이터로 표시한다.
            return Optional.of(new ExchangeRate(cached.get().rate(), true));
        }

        log.info("캐시된 시세가 {}분을 초과했습니다. 실시간 API 를 재조회합니다. {}/{}",
                MAX_AGE.toMinutes(), base, quote);
        return Optional.empty();
    }

    /**
     * 공급자 장애 시의 열화(degradation) 정책입니다. 모든 어댑터의 폴백이 이 메서드를 공유합니다.
     *
     * @param base  기준 자산 코드
     * @param quote 표시 통화 코드
     * @param cause 공급자 호출을 실패시킨 원인
     * @return 캐시에서 가져온 지연 시세
     * @throws UnsupportedAssetCodeException  원인이 영구 오류인 경우 그대로 전파
     * @throws ArbitrageRiskException         캐시가 허용 나이를 넘긴 경우
     * @throws MarketDataUnavailableException 캐시까지 비어 있는 경우
     */
    public ExchangeRate degradeOrThrow(String base, String quote, Throwable cause) {
        // 지원하지 않는 코드는 캐시로도 해결되지 않는 영구 오류다. 폴백이 삼키면 클라이언트가
        // 일시적 장애로 오인해 무한 재시도한다.
        if (cause instanceof UnsupportedAssetCodeException permanent) {
            throw permanent;
        }

        log.warn("[Fallback 작동] {}/{} 시세 조회 실패. 캐시 기반 성능 저하 시도. 사유: {}",
                base, quote, cause.getMessage());

        CachedRate cached = read(base, quote)
                .orElseThrow(() -> new MarketDataUnavailableException(
                        "외부 시세 API 장애 및 캐시 고갈로 " + base + "/" + quote
                                + " 시세를 확보할 수 없습니다.", cause));

        // 타임스탬프가 있는데 허용 나이를 넘겼다면 거래를 차단한다. 낡은 시세로 체결하면
        // 차익거래에 노출된다.
        if (cached.cachedAt() != null && !cached.isFresh()) {
            throw new ArbitrageRiskException("시세 데이터 만료(" + MAX_AGE.toMinutes()
                    + "분 초과). 재정적 손실 방지를 위해 거래를 원천 차단합니다.");
        }

        return new ExchangeRate(cached.rate(), true);
    }

    private void write(String key, BigDecimal rate, long epochMillis) {
        try {
            redisTemplate.opsForValue().set(key, rate.toPlainString() + "|" + epochMillis, TTL);
        } catch (Exception e) {
            log.error("Redis 시세 캐시 쓰기 실패 (인프라 글리치) key={}: {}", key, e.getMessage());
        }
    }

    /**
     * 캐시된 시세 한 건.
     *
     * @param rate     1 base 의 quote 표시 가치
     * @param cachedAt 캐시된 시각. 타임스탬프 없는 구버전 캐시는 null
     */
    public record CachedRate(BigDecimal rate, Instant cachedAt) {

        /** 허용 나이 이내인지 여부. 타임스탬프가 없으면 나이를 알 수 없으므로 지연 데이터로 본다. */
        public boolean isFresh() {
            return cachedAt != null
                    && Duration.between(cachedAt, Instant.now()).compareTo(MAX_AGE) <= 0;
        }
    }
}
