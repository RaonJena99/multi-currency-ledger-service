package com.github.raonjena99.multi_currency_ledger_service.common.infrastructure.adapter;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import com.github.raonjena99.multi_currency_ledger_service.common.exception.UnsupportedAssetCodeException;
import com.github.raonjena99.multi_currency_ledger_service.common.port.ExchangeRateProvider.ExchangeRate;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import io.micrometer.core.annotation.Timed;
import lombok.extern.slf4j.Slf4j;

/**
 * 암호화폐 시세를 CoinGecko 에서 조회하는 어댑터입니다.
 *
 * <p>암호화폐를 법정화폐로 <b>직접 호가</b>받는 것이 이 어댑터의 존재 이유입니다. fxratesapi 도
 * 암호화폐를 다루지만 그 값은 USD 교차환산이라 실측에서 0.15% 차이가 났습니다
 * ({@code BTC→KRW}: fxratesapi 106,995,446 vs CoinGecko 106,837,062). 암호화폐는 암호화폐
 * 네이티브 소스를 신뢰합니다.
 *
 * <p>실측 응답:
 * <pre>
 * GET /api/v3/simple/price?ids=bitcoin,ethereum&amp;vs_currencies=krw,usd
 * {"bitcoin":{"krw":106879900,"usd":77133},"ethereum":{"usd":2445.77,"krw":3388999}}
 * </pre>
 *
 * <p><b>조회 방향을 항상 "암호화폐 → 법정화폐"로 고정합니다.</b> 그 방향이 큰 값이라 유효숫자가
 * 남습니다. 반대 방향({@code KRW→BTC})이 필요하면 큰 방향을 조회해 {@code BigDecimal} 로 역수를
 * 취합니다. 공급자에게 작은 값을 직접 물어보면 양자화로 유효숫자가 날아갑니다.
 */
@Slf4j
@Component
@Profile("!local & !test & !dev")
public class CoinGeckoAdapter {

    private static final ParameterizedTypeReference<Map<String, Map<String, BigDecimal>>> PRICE_TYPE =
            new ParameterizedTypeReference<>() {};

    /** CoinGecko 데모 플랜 키 헤더. 키가 없으면 붙이지 않습니다. */
    private static final String DEMO_KEY_HEADER = "x-cg-demo-api-key";

    private final RestClient restClient;
    private final ExchangeRateCache cache;
    private final CryptoAssetProperties cryptoAssets;
    private final String apiKey;

    /**
     * @param restClient   base URL 과 타임아웃이 설정된 전용 클라이언트
     * @param cache        공용 시세 캐시
     * @param cryptoAssets 심볼 → coin id 매핑
     * @param apiKey       CoinGecko 데모 키. 무료 사용 시 비워 두면 됩니다
     */
    public CoinGeckoAdapter(
            @Qualifier("coinGeckoRestClient") RestClient restClient,
            ExchangeRateCache cache,
            CryptoAssetProperties cryptoAssets,
            @Value("${ledger.external.crypto.api-key:}") String apiKey) {
        this.restClient = restClient;
        this.cache = cache;
        this.cryptoAssets = cryptoAssets;
        this.apiKey = apiKey;
    }

    /**
     * 1 base 의 quote 표시 가치를 조회합니다. 둘 중 한쪽은 등록된 암호화폐여야 합니다.
     *
     * @param base  기준 자산 코드 (대문자)
     * @param quote 표시 통화 코드 (대문자)
     * @return 조회된 시세. 캐시에서 가져온 지연 데이터면 {@code isStale=true}
     */
    @Timed(value = "external.api.crypto_price.response", description = "Time taken to fetch crypto price")
    // fallbackMethod 는 가장 바깥 애노테이션에만 둔다. 내부 @Retry 에도 두면 폴백이 예외를 삼켜
    // 서킷 브레이커가 실패를 관측하지 못한다.
    @Retry(name = "cryptoPriceApi")
    @CircuitBreaker(name = "cryptoPriceApi", fallbackMethod = "fallbackRate")
    public ExchangeRate getRate(String base, String quote) {
        var cached = cache.lookup(base, quote);
        if (cached.isPresent()) {
            return cached.get();
        }

        // 큰 값이 나오는 방향(암호화폐가 base)으로 조회하고, 필요하면 로컬에서 역수를 취한다.
        boolean baseIsCrypto = cryptoAssets.isCrypto(base);
        String cryptoCode = baseIsCrypto ? base : quote;
        String fiatCode = baseIsCrypto ? quote : base;

        BigDecimal quoted = fetchOne(cryptoCode, fiatCode);
        BigDecimal rate = baseIsCrypto
                ? quoted
                : BigDecimal.ONE.divide(quoted, ExchangeRateCache.RATE_SCALE, RoundingMode.HALF_EVEN);

        cache.writeBothDirections(base, quote, rate);
        return new ExchangeRate(rate, false);
    }

    /**
     * 여러 암호화폐의 시세를 <b>한 번의 호출</b>로 조회합니다.
     *
     * <p>CoinGecko 는 {@code ids} 와 {@code vs_currencies} 를 모두 복수로 받으므로, 포트폴리오
     * 평가에서 자산 수만큼 호출하는 N+1 을 원천 제거할 수 있습니다. 이 최적화가 실패하면
     * 호출자가 단건 경로로 되돌아가면 되므로 복원력 애노테이션을 붙이지 않습니다.
     *
     * @param cryptoCodes 조회할 암호화폐 심볼 목록 (대문자)
     * @param fiatCode    표시 통화 코드 (대문자)
     * @return 심볼 → 1 심볼당 표시 통화 금액. 응답에 없는 심볼은 결과에서 제외됩니다
     */
    public Map<String, BigDecimal> getRates(List<String> cryptoCodes, String fiatCode) {
        Map<String, String> idsBySymbol = new LinkedHashMap<>();
        for (String code : cryptoCodes) {
            String coinId = cryptoAssets.coinIdOf(code);
            if (coinId != null) {
                idsBySymbol.put(code, coinId);
            }
        }
        if (idsBySymbol.isEmpty()) {
            return Map.of();
        }

        Map<String, Map<String, BigDecimal>> response =
                requestPrices(String.join(",", idsBySymbol.values()), fiatCode);

        String vsKey = fiatCode.toLowerCase();
        Map<String, BigDecimal> result = new LinkedHashMap<>();
        idsBySymbol.forEach((symbol, coinId) -> {
            Map<String, BigDecimal> prices = response == null ? null : response.get(coinId);
            BigDecimal rate = prices == null ? null : prices.get(vsKey);
            if (rate != null && rate.signum() > 0) {
                result.put(symbol, rate);
                cache.writeBothDirections(symbol, fiatCode, rate);
            }
        });
        return result;
    }

    private BigDecimal fetchOne(String cryptoCode, String fiatCode) {
        String coinId = cryptoAssets.coinIdOf(cryptoCode);
        if (coinId == null) {
            throw new UnsupportedAssetCodeException(
                    "CoinGecko coin id 가 설정되지 않은 암호화폐 심볼입니다: " + cryptoCode);
        }

        Map<String, Map<String, BigDecimal>> response = requestPrices(coinId, fiatCode);
        Map<String, BigDecimal> prices = response == null ? null : response.get(coinId);
        BigDecimal rate = prices == null ? null : prices.get(fiatCode.toLowerCase());

        if (rate == null || rate.signum() <= 0) {
            // CoinGecko 는 모르는 vs_currencies 를 오류가 아니라 빈 객체로 응답한다.
            // 재시도해도 같으므로 영구 오류로 분류해 서킷 브레이커가 장애로 집계하지 않게 한다.
            throw new UnsupportedAssetCodeException(
                    "CoinGecko 가 " + cryptoCode + "/" + fiatCode + " 시세를 제공하지 않습니다.");
        }
        return rate;
    }

    private Map<String, Map<String, BigDecimal>> requestPrices(String ids, String fiatCode) {
        return restClient.get()
                .uri(builder -> builder.path("/api/v3/simple/price")
                        .queryParam("ids", ids)
                        .queryParam("vs_currencies", fiatCode.toLowerCase())
                        .build())
                .headers(headers -> {
                    if (StringUtils.hasText(apiKey)) {
                        headers.set(DEMO_KEY_HEADER, apiKey);
                    }
                })
                .retrieve()
                .body(PRICE_TYPE);
    }

    /**
     * 외부 API 실패 시의 복원력 방어선입니다.
     *
     * @param base  기준 자산 코드
     * @param quote 표시 통화 코드
     * @param t     원인 예외
     * @return 캐시에서 가져온 지연 시세
     */
    public ExchangeRate fallbackRate(String base, String quote, Throwable t) {
        return cache.degradeOrThrow(base, quote, t);
    }
}
