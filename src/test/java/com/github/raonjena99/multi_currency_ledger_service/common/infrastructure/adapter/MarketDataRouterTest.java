package com.github.raonjena99.multi_currency_ledger_service.common.infrastructure.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.github.raonjena99.multi_currency_ledger_service.common.exception.UnsupportedAssetCodeException;
import com.github.raonjena99.multi_currency_ledger_service.common.port.ExchangeRateProvider.ExchangeRate;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("마켓데이터 라우터: 자산군별 공급자 분기")
class MarketDataRouterTest {

    @Mock private FxRatesApiAdapter fiatAdapter;
    @Mock private CoinGeckoAdapter cryptoAdapter;
    @Mock private ExchangeRateCache cache;

    private MarketDataRouter router;

    @BeforeEach
    void setUp() {
        CryptoAssetProperties props = new CryptoAssetProperties();
        props.setSymbolIds(Map.of("BTC", "bitcoin", "ETH", "ethereum"));
        router = new MarketDataRouter(fiatAdapter, cryptoAdapter, props, cache);

        when(cache.lookup(anyString(), anyString())).thenReturn(Optional.empty());
    }

    @Test
    @DisplayName("같은 코드는 공급자를 거치지 않고 환율 1을 반환한다")
    void sameCodeShortCircuits() {
        ExchangeRate result = router.getExchangeRate("KRW", "KRW");

        assertThat(result.rate()).isEqualByComparingTo("1");
        assertThat(result.isStale()).isFalse();
        verifyNoInteractions(fiatAdapter, cryptoAdapter);
    }

    @Test
    @DisplayName("대소문자와 공백이 달라도 같은 코드로 취급한다")
    void normalizesCodes() {
        assertThat(router.getExchangeRate(" krw ", "KRW").rate()).isEqualByComparingTo("1");
    }

    @Test
    @DisplayName("법정화폐 쌍은 fxratesapi 로 보낸다")
    void routesFiatPairToFxRatesApi() {
        when(fiatAdapter.getRate("USD", "KRW"))
                .thenReturn(new ExchangeRate(new BigDecimal("1384.72"), false));

        assertThat(router.getExchangeRate("USD", "KRW").rate()).isEqualByComparingTo("1384.72");
        verify(cryptoAdapter, never()).getRate(anyString(), anyString());
    }

    @Test
    @DisplayName("암호화폐가 base 면 CoinGecko 로 보낸다")
    void routesCryptoBaseToCoinGecko() {
        when(cryptoAdapter.getRate("BTC", "KRW"))
                .thenReturn(new ExchangeRate(new BigDecimal("106879900"), false));

        assertThat(router.getExchangeRate("BTC", "KRW").rate()).isEqualByComparingTo("106879900");
        verify(fiatAdapter, never()).getRate(anyString(), anyString());
    }

    @Test
    @DisplayName("암호화폐가 quote 여도 CoinGecko 로 보낸다 - 정밀도가 좋은 쪽이 처리한다")
    void routesCryptoQuoteToCoinGecko() {
        when(cryptoAdapter.getRate("KRW", "BTC"))
                .thenReturn(new ExchangeRate(new BigDecimal("9.35e-9"), false));

        router.getExchangeRate("KRW", "BTC");

        verify(cryptoAdapter).getRate("KRW", "BTC");
        verify(fiatAdapter, never()).getRate(anyString(), anyString());
    }

    @Test
    @DisplayName("주식 코드는 공급자가 없으므로 명확히 거부한다")
    void rejectsStockCode() {
        assertThatThrownBy(() -> router.getExchangeRate("AAPL", "USD"))
                .isInstanceOf(UnsupportedAssetCodeException.class)
                .hasMessageContaining("AAPL");

        verifyNoInteractions(fiatAdapter, cryptoAdapter);
    }

    @Test
    @DisplayName("ISO 4217 이 아닌 임의 코드도 거부한다")
    void rejectsUnknownCode() {
        assertThatThrownBy(() -> router.getExchangeRate("POINTS", "KRW"))
                .isInstanceOf(UnsupportedAssetCodeException.class);
    }

    @Test
    @DisplayName("다중 조회: 표시 통화 자신은 환율 1로 채운다")
    void batchFillsIdentityRate() {
        Map<String, ExchangeRate> result = router.getExchangeRates(List.of("KRW"), "KRW");

        assertThat(result.get("KRW").rate()).isEqualByComparingTo("1");
        verifyNoInteractions(fiatAdapter, cryptoAdapter);
    }

    @Test
    @DisplayName("다중 조회: 캐시 히트는 공급자를 호출하지 않는다")
    void batchUsesCacheHits() {
        when(cache.lookup("USD", "KRW"))
                .thenReturn(Optional.of(new ExchangeRate(new BigDecimal("1384.72"), false)));

        Map<String, ExchangeRate> result = router.getExchangeRates(List.of("USD"), "KRW");

        assertThat(result.get("USD").rate()).isEqualByComparingTo("1384.72");
        verifyNoInteractions(fiatAdapter, cryptoAdapter);
    }

    @Test
    @DisplayName("다중 조회: 암호화폐 미스가 2건 이상이면 1회 호출로 묶는다")
    void batchGroupsCryptoMisses() {
        when(cryptoAdapter.getRates(List.of("BTC", "ETH"), "KRW"))
                .thenReturn(Map.of("BTC", new BigDecimal("106879900"), "ETH", new BigDecimal("3388999")));

        Map<String, ExchangeRate> result = router.getExchangeRates(List.of("BTC", "ETH"), "KRW");

        assertThat(result.get("BTC").rate()).isEqualByComparingTo("106879900");
        assertThat(result.get("ETH").rate()).isEqualByComparingTo("3388999");
        verify(cryptoAdapter).getRates(List.of("BTC", "ETH"), "KRW");
        verify(cryptoAdapter, never()).getRate(anyString(), anyString());
    }

    @Test
    @DisplayName("다중 조회: 배치가 실패하면 단건 경로로 되돌아간다")
    void batchFailureFallsBackToSingleCalls() {
        when(cryptoAdapter.getRates(anyList(), anyString()))
                .thenThrow(new RuntimeException("CoinGecko down"));
        when(cryptoAdapter.getRate("BTC", "KRW"))
                .thenReturn(new ExchangeRate(new BigDecimal("106000000"), true));
        when(cryptoAdapter.getRate("ETH", "KRW"))
                .thenReturn(new ExchangeRate(new BigDecimal("3300000"), true));

        Map<String, ExchangeRate> result = router.getExchangeRates(List.of("BTC", "ETH"), "KRW");

        assertThat(result).hasSize(2);
        assertThat(result.get("BTC").isStale()).isTrue();
        verify(cryptoAdapter).getRate("BTC", "KRW");
        verify(cryptoAdapter).getRate("ETH", "KRW");
    }

    @Test
    @DisplayName("다중 조회: 배치가 일부만 채우면 나머지는 단건으로 메운다")
    void batchPartialResultIsCompletedIndividually() {
        when(cryptoAdapter.getRates(List.of("BTC", "ETH"), "KRW"))
                .thenReturn(Map.of("BTC", new BigDecimal("106879900")));
        when(cryptoAdapter.getRate("ETH", "KRW"))
                .thenReturn(new ExchangeRate(new BigDecimal("3388999"), false));

        Map<String, ExchangeRate> result = router.getExchangeRates(List.of("BTC", "ETH"), "KRW");

        assertThat(result).hasSize(2);
        verify(cryptoAdapter).getRate("ETH", "KRW");
    }

    @Test
    @DisplayName("다중 조회: 암호화폐 미스가 1건이면 배치를 쓰지 않는다")
    void batchSkippedForSingleCryptoMiss() {
        when(cryptoAdapter.getRate("BTC", "KRW"))
                .thenReturn(new ExchangeRate(new BigDecimal("106879900"), false));

        router.getExchangeRates(List.of("BTC"), "KRW");

        verify(cryptoAdapter, never()).getRates(anyList(), anyString());
        verify(cryptoAdapter).getRate("BTC", "KRW");
    }

    @Test
    @DisplayName("다중 조회: 법정화폐와 암호화폐가 섞여도 각자 공급자로 간다")
    void batchMixesAssetClasses() {
        when(cryptoAdapter.getRates(List.of("BTC", "ETH"), "KRW"))
                .thenReturn(Map.of("BTC", new BigDecimal("106879900"), "ETH", new BigDecimal("3388999")));
        when(fiatAdapter.getRate("USD", "KRW"))
                .thenReturn(new ExchangeRate(new BigDecimal("1384.72"), false));

        Map<String, ExchangeRate> result = router.getExchangeRates(List.of("BTC", "ETH", "USD", "KRW"), "KRW");

        assertThat(result).hasSize(4);
        assertThat(result.get("KRW").rate()).isEqualByComparingTo("1");
        assertThat(result.get("USD").rate()).isEqualByComparingTo("1384.72");
        verify(fiatAdapter).getRate("USD", "KRW");
    }
}
