package com.github.raonjena99.multi_currency_ledger_service.common.infrastructure.adapter;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.github.raonjena99.multi_currency_ledger_service.common.exception.UnsupportedAssetCodeException;
import com.github.raonjena99.multi_currency_ledger_service.common.port.ExchangeRateProvider;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 자산 코드에 따라 시세 공급자를 고르는 유일한 {@link ExchangeRateProvider} 구현체입니다.
 *
 * <p><b>왜 어댑터가 아니라 라우터가 포트를 구현하는가.</b> 어댑터마다 포트를 구현하면 같은 타입
 * 빈이 여럿 생깁니다. 그러면 {@code @Primary} 나 한정자에 의존해야 하고, 실수 한 번으로 프로덕션
 * 거래에 엉뚱한 공급자가 붙습니다. 실제로 이전 구현은 {@code @Primary} 가 프로파일 게이팅을
 * 이겨서 개발 환경에서도 실서비스 어댑터가 선택되고 있었습니다. 구현체를 하나로 유지하면 그
 * 실수의 여지 자체가 없어집니다.
 *
 * <p><b>왜 포트 시그니처에 {@code AssetType} 을 넣지 않았는가.</b> "BTC 가 암호화폐"라는 사실은
 * 호출자의 사정이 아니라 마켓데이터 계층의 성질입니다. 게다가 {@code AssetType} 을 인자로 넣으면
 * {@code AccountApi} → {@code PortfolioCacheDto}(Redis 직렬화) → {@code PortfolioViewRefresher}
 * 까지 타입이 번집니다. 분류 책임을 이 클래스가 갖는 편이 경계가 깔끔합니다.
 *
 * <p>분기 규칙은 다음 순서입니다.
 * <ol>
 *   <li>양쪽이 같은 코드 → 환율 1</li>
 *   <li>한쪽이라도 등록된 암호화폐 → {@link CoinGeckoAdapter}</li>
 *   <li>양쪽이 ISO 4217 통화 → {@link FxRatesApiAdapter}</li>
 *   <li>그 외 → {@link UnsupportedAssetCodeException}</li>
 * </ol>
 * 4번이 {@code AAPL} 같은 주식 코드를 걸러냅니다. 무료 시세 공급자는 주식을 다루지 않으므로,
 * 모호한 빈 응답 대신 "지원하지 않는다"는 명확한 신호를 돌려줍니다.
 */
@Slf4j
@Component
@Profile("!local & !test & !dev")
@RequiredArgsConstructor
public class MarketDataRouter implements ExchangeRateProvider {

    /**
     * ISO 4217 통화 코드 집합. JDK 가 아는 통화로 한 번만 만든다.
     *
     * <p>판정 결과를 요청 코드별로 캐시하면 안 된다. 자산 코드는 클라이언트가 정하므로 서로 다른 코드를
     * 반복해 보내는 것만으로 지워지지 않는 항목이 계속 쌓인다. 고정 집합이면 예외 없이 O(1) 로 판정된다.
     */
    private static final Set<String> ISO_CURRENCY_CODES = Currency.getAvailableCurrencies().stream()
            .map(Currency::getCurrencyCode)
            .collect(Collectors.toUnmodifiableSet());

    private final FxRatesApiAdapter fiatAdapter;
    private final CoinGeckoAdapter cryptoAdapter;
    private final CryptoAssetProperties cryptoAssets;
    private final ExchangeRateCache cache;

    @Override
    public ExchangeRate getExchangeRate(String baseAsset, String targetAsset) {
        String base = normalize(baseAsset);
        String quote = normalize(targetAsset);

        if (base.equals(quote)) {
            return new ExchangeRate(BigDecimal.ONE, false);
        }
        if (cryptoAssets.isCrypto(base) || cryptoAssets.isCrypto(quote)) {
            return cryptoAdapter.getRate(base, quote);
        }
        if (isFiat(base) && isFiat(quote)) {
            return fiatAdapter.getRate(base, quote);
        }

        throw new UnsupportedAssetCodeException(String.format(
                "%s/%s 시세를 제공할 공급자가 없습니다. ISO 4217 통화도, 설정된 암호화폐 심볼도 아닙니다. "
                        + "주식 등 다른 자산군을 거래하려면 해당 자산군을 다루는 시세 어댑터를 추가해야 합니다.",
                base, quote));
    }

    /**
     * 여러 자산의 시세를 한 번에 조회합니다.
     *
     * <p>캐시를 먼저 훑고, 남은 암호화폐는 CoinGecko 다중 조회로 <b>1회 호출</b>에 묶습니다.
     * 무료 플랜의 좁은 쿼터(fxratesapi 분당 61건)에서 포트폴리오 조회가 자산 수만큼 호출하면
     * 금방 고갈되기 때문입니다.
     *
     * @param targetAssets 조회할 자산 코드 목록
     * @param baseCurrency 표시 통화 코드
     * @return 입력 코드 그대로를 키로 갖는 시세 맵
     */
    @Override
    public Map<String, ExchangeRate> getExchangeRates(List<String> targetAssets, String baseCurrency) {
        String quote = normalize(baseCurrency);
        Map<String, ExchangeRate> result = new LinkedHashMap<>();
        List<String> misses = new ArrayList<>();

        for (String requested : targetAssets) {
            String asset = normalize(requested);
            if (asset.equals(quote)) {
                result.put(requested, new ExchangeRate(BigDecimal.ONE, false));
                continue;
            }
            cache.lookup(asset, quote).ifPresentOrElse(
                    hit -> result.put(requested, hit),
                    () -> misses.add(requested));
        }

        boolean cryptoDown = !batchFetchCrypto(misses, quote, result);
        boolean fiatDown = false;

        // 배치로 채우지 못한 나머지는 단건 경로로 처리한다.
        //
        // 공급자가 응답 없이 멈춘 경우 단건 조회는 건당 (재시도 3회 × 읽기 타임아웃)을 기다린다.
        // 한 번 실패한 공급자에 남은 자산을 계속 물으면 조회 한 번이 수십 초로 늘어나므로,
        // 그 공급자의 나머지 자산은 캐시로만 채운다.
        for (String requested : misses) {
            if (result.containsKey(requested)) {
                continue;
            }
            boolean crypto = isCryptoPair(requested, quote);
            if (crypto ? cryptoDown : fiatDown) {
                fillFromCache(requested, quote, result);
                continue;
            }
            if (!fetchForDisplay(requested, quote, result)) {
                if (crypto) {
                    cryptoDown = true;
                } else {
                    fiatDown = true;
                }
            }
        }
        return result;
    }

    /**
     * 조회 화면용 단건 시세를 채웁니다.
     *
     * <p>단건 경로의 폴백은 허용 나이를 넘긴 캐시를 거래 보호를 위해 예외로 막습니다. 이 메서드는
     * 거래가 아닌 조회에서만 쓰이므로, 공급자 장애 시 낡은 캐시라도 지연 데이터로 보여줍니다.
     * 캐시마저 없으면 결과에서 빼고, 호출자가 평가액 없이 지연 데이터로 표시합니다.
     *
     * <p>지원하지 않는 자산(상장 폐지된 코인 등)도 예외를 던지지 않고 결과에서 뺍니다. 던지면 그
     * 자산 하나 때문에 포트폴리오 전체가 422 가 되어, 해당 계좌는 조회 자체를 할 수 없게 됩니다.
     *
     * @return 공급자 호출이 일시 장애로 실패했으면 false
     */
    private boolean fetchForDisplay(String requested, String quote, Map<String, ExchangeRate> result) {
        try {
            result.put(requested, getExchangeRate(requested, quote));
            return true;
        } catch (UnsupportedAssetCodeException e) {
            log.warn("{}/{} 시세를 제공하는 공급자가 없어 평가액 없이 표시합니다: {}",
                    requested, quote, e.getMessage());
            return true;
        } catch (RuntimeException e) {
            log.warn("{}/{} 시세 조회 실패. 캐시된 시세를 지연 데이터로 표시합니다: {}",
                    requested, quote, e.getMessage());
            fillFromCache(requested, quote, result);
            return false;
        }
    }

    private void fillFromCache(String requested, String quote, Map<String, ExchangeRate> result) {
        cache.read(normalize(requested), quote)
                .ifPresent(cached -> result.put(requested, new ExchangeRate(cached.rate(), true)));
    }

    private boolean isCryptoPair(String requested, String quote) {
        return cryptoAssets.isCrypto(normalize(requested)) || cryptoAssets.isCrypto(quote);
    }

    /**
     * 암호화폐 미스를 CoinGecko 다중 조회로 한 번에 채웁니다.
     *
     * <p>일부만 채워진 경우 나머지는 호출자가 단건 경로로 처리합니다. 호출 자체가 실패하면
     * 공급자 장애로 보고 false 를 반환해, 호출자가 단건 재조회 없이 캐시로 채우게 합니다.
     *
     * @return 다중 조회 호출이 실패했으면 false
     */
    private boolean batchFetchCrypto(List<String> misses, String quote, Map<String, ExchangeRate> result) {
        List<String> cryptoMisses = misses.stream()
                .filter(requested -> cryptoAssets.isCrypto(normalize(requested)))
                .toList();
        if (cryptoMisses.size() < 2 || !isFiat(quote)) {
            return true;
        }

        try {
            Map<String, BigDecimal> batched = cryptoAdapter.getRates(
                    cryptoMisses.stream().map(this::normalize).toList(), quote);

            for (String requested : cryptoMisses) {
                BigDecimal rate = batched.get(normalize(requested));
                if (rate != null) {
                    result.put(requested, new ExchangeRate(rate, false));
                }
            }
            return true;
        } catch (Exception e) {
            log.warn("암호화폐 시세 다중 조회 실패. 캐시된 시세로 표시합니다: {}", e.getMessage());
            return false;
        }
    }

    private String normalize(String assetCode) {
        return assetCode == null ? "" : assetCode.trim().toUpperCase();
    }

    private boolean isFiat(String assetCode) {
        return assetCode != null && ISO_CURRENCY_CODES.contains(assetCode);
    }
}
