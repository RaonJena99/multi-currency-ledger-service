package com.github.raonjena99.multi_currency_ledger_service.common.infrastructure.adapter;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import com.github.raonjena99.multi_currency_ledger_service.common.exception.MarketDataUnavailableException;
import com.github.raonjena99.multi_currency_ledger_service.common.exception.UnsupportedAssetCodeException;
import com.github.raonjena99.multi_currency_ledger_service.common.port.ExchangeRateProvider.ExchangeRate;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import io.micrometer.core.annotation.Timed;
import lombok.extern.slf4j.Slf4j;

/**
 * 법정화폐 환율을 fxratesapi.com 에서 조회하는 어댑터입니다.
 *
 * <p><b>포트를 구현하지 않습니다.</b>
 * {@link com.github.raonjena99.multi_currency_ledger_service.common.port.ExchangeRateProvider}
 * 를 구현하는 것은 {@link MarketDataRouter} 하나뿐입니다. 어댑터마다 포트를 구현하면 같은 타입
 * 빈이 여럿 생겨 한정자 실수 한 번으로 엉뚱한 공급자가 주입됩니다.
 *
 * <p><b>정밀도 가드가 이 클래스의 핵심입니다.</b> 이 API 는 환율을 절대 소수 자릿수(최대 15자리)로
 * 양자화합니다. 그래서 1 에 크게 못 미치는 환율은 유효숫자가 통째로 날아갑니다.
 * <pre>
 * base=BTC → KRW = 106995446.66022733   (유효숫자 17자리)
 * base=KRW → BTC = 9e-9                 (유효숫자 1자리, 참값 9.3461e-9 → 오차 3.7%)
 * </pre>
 * {@code places} 파라미터로도 복구되지 않습니다({@code places=9/12/15} 모두 동일 값 반환).
 * 그래서 값이 작으면 <b>큰 방향으로 다시 조회해 역수를 로컬에서 계산</b>합니다.
 *
 * <p>무료 플랜은 API 키 없이 동작하며 분당 61건입니다({@code x-ratelimit-limit: 61}).
 * 좁은 쿼터는 {@link ExchangeRateCache} 의 역방향 동시 기록으로 보완합니다.
 */
@Slf4j
@Component
@Profile("!local & !test & !dev")
public class FxRatesApiAdapter {

    private final RestClient restClient;
    private final ExchangeRateCache cache;
    private final String apiKey;

    /**
     * 이 값 미만의 환율은 유효숫자가 부족하다고 보고 역방향 조회로 대체합니다.
     *
     * <p>기본값 {@code 0.0001} 은 소수 9자리 양자화에서 유효숫자 5자리 이상을 보장하는 선입니다.
     * 문턱을 더 높이면 {@code USD→EUR}(0.857) 처럼 정밀도가 충분한 흔한 쌍까지 호출이 두 배가
     * 되어 좁은 쿼터를 낭비합니다.
     */
    private final BigDecimal precisionFloor;

    /**
     * 필드 주입 대신 명시적 생성자를 씁니다. 필드에 {@code @Value} 를 달면 단위 테스트에서
     * 직접 생성한 인스턴스의 임계값이 null 이 되어 가드가 조용히 다르게 동작합니다.
     *
     * @param restClient     base URL 과 타임아웃이 설정된 전용 클라이언트
     * @param cache          공용 시세 캐시
     * @param apiKey         API 키. 무료 플랜은 비워 두면 됩니다
     * @param precisionFloor 역방향 조회로 대체할 환율 하한
     */
    public FxRatesApiAdapter(
            @Qualifier("fxRatesRestClient") RestClient restClient,
            ExchangeRateCache cache,
            @Value("${ledger.external.exchange-rate.api-key:}") String apiKey,
            @Value("${ledger.external.exchange-rate.precision-floor:0.0001}") BigDecimal precisionFloor) {
        this.restClient = restClient;
        this.cache = cache;
        this.apiKey = apiKey;
        this.precisionFloor = precisionFloor;
    }

    /**
     * 1 base 의 quote 표시 가치를 조회합니다.
     *
     * @param base  기준 통화 코드 (대문자)
     * @param quote 표시 통화 코드 (대문자)
     * @return 조회된 환율. 캐시에서 가져온 지연 데이터면 {@code isStale=true}
     */
    @Timed(value = "external.api.exchange_rate.response", description = "Time taken to fetch fiat exchange rate")
    // fallbackMethod 는 가장 바깥 애노테이션(@CircuitBreaker)에만 둔다.
    // 내부 @Retry 에도 두면 Retry 의 폴백이 예외를 삼켜 외부 서킷 브레이커가 성공만 관측하고
    // 실패율 임계치에 절대 도달하지 못한다(서킷이 열리지 않음).
    @Retry(name = "exchangeRateApi")
    @CircuitBreaker(name = "exchangeRateApi", fallbackMethod = "fallbackRate")
    public ExchangeRate getRate(String base, String quote) {
        var cached = cache.lookup(base, quote);
        if (cached.isPresent()) {
            return cached.get();
        }

        BigDecimal rate = resolveWithPrecisionGuard(base, quote);
        if (rate == null) {
            return fallbackRate(base, quote, new MarketDataUnavailableException(
                    "fxratesapi 응답에 " + quote + " 환율이 없습니다."));
        }

        cache.writeBothDirections(base, quote, rate);
        return new ExchangeRate(rate, false);
    }

    /**
     * 정밀도가 확보되는 방향으로 조회합니다. 정방향 값이 하한 미만이면 역방향을 조회해
     * 역수를 취합니다.
     */
    private BigDecimal resolveWithPrecisionGuard(String base, String quote) {
        BigDecimal direct = fetch(base, quote);
        if (direct != null && direct.compareTo(precisionFloor) >= 0) {
            return direct;
        }

        BigDecimal inverse = fetch(quote, base);
        if (inverse == null || inverse.signum() <= 0) {
            return direct;
        }

        log.debug("{}/{} 환율이 하한 미만이라 역방향 조회로 정밀도를 확보했습니다.", base, quote);
        return BigDecimal.ONE.divide(inverse, ExchangeRateCache.RATE_SCALE, RoundingMode.HALF_EVEN);
    }

    private BigDecimal fetch(String base, String quote) {
        FxRatesApiResponse response = restClient.get()
                .uri(builder -> {
                    builder.path("/latest")
                            .queryParam("base", base)
                            .queryParam("currencies", quote);
                    if (StringUtils.hasText(apiKey)) {
                        builder.queryParam("api_key", apiKey);
                    }
                    return builder.build();
                })
                .retrieve()
                // 400/404 는 지원하지 않는 통화 코드다. 재시도해도 결과가 같으므로 전용 예외로
                // 분류해 서킷 브레이커가 장애로 집계하지 않게 한다. 429 등 나머지 4xx 는
                // 일시적이므로 기본 처리에 맡겨 재시도 대상으로 남긴다.
                .onStatus(status -> status.value() == HttpStatus.BAD_REQUEST.value()
                                 || status.value() == HttpStatus.NOT_FOUND.value(),
                        (req, res) -> {
                            throw new UnsupportedAssetCodeException(
                                    "fxratesapi 가 지원하지 않는 통화 코드입니다: " + base + "/" + quote);
                        })
                .body(FxRatesApiResponse.class);

        if (response == null) {
            return null;
        }
        if (!response.success()) {
            throw new UnsupportedAssetCodeException(
                    "fxratesapi 오류(" + response.error() + "): " + response.description());
        }

        Map<String, BigDecimal> rates = response.rates();
        return rates == null ? null : rates.get(quote);
    }

    /**
     * 외부 API 실패 시의 복원력 방어선입니다. 캐시된 시세로 성능 저하 대응을 시도합니다.
     *
     * @param base  기준 통화 코드
     * @param quote 표시 통화 코드
     * @param t     원인 예외
     * @return 캐시에서 가져온 지연 시세
     */
    public ExchangeRate fallbackRate(String base, String quote, Throwable t) {
        return cache.degradeOrThrow(base, quote, t);
    }
}
