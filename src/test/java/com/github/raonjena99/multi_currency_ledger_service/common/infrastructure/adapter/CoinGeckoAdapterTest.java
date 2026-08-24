package com.github.raonjena99.multi_currency_ledger_service.common.infrastructure.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

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
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.github.raonjena99.multi_currency_ledger_service.common.exception.UnsupportedAssetCodeException;
import com.github.raonjena99.multi_currency_ledger_service.common.port.ExchangeRateProvider.ExchangeRate;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("CoinGecko 어댑터: 조회 방향 고정과 다중 조회")
class CoinGeckoAdapterTest {

    @Mock private ExchangeRateCache cache;

    private MockRestServiceServer mockServer;
    private CoinGeckoAdapter adapter;

    @BeforeEach
    void setUp() {
        CryptoAssetProperties props = new CryptoAssetProperties();
        props.setSymbolIds(Map.of("BTC", "bitcoin", "ETH", "ethereum"));

        RestClient.Builder builder = RestClient.builder();
        mockServer = MockRestServiceServer.bindTo(builder).build();
        adapter = new CoinGeckoAdapter(builder.build(), cache, props, "");

        when(cache.lookup(anyString(), anyString())).thenReturn(Optional.empty());
    }

    @Test
    @DisplayName("암호화폐가 base 면 직접 호가를 그대로 사용한다")
    void directQuoteWhenBaseIsCrypto() {
        mockServer.expect(requestTo("/api/v3/simple/price?ids=bitcoin&vs_currencies=krw"))
                .andRespond(withSuccess("{\"bitcoin\":{\"krw\":106879900}}", MediaType.APPLICATION_JSON));

        ExchangeRate result = adapter.getRate("BTC", "KRW");

        assertThat(result.rate()).isEqualByComparingTo("106879900");
        assertThat(result.isStale()).isFalse();
        verify(cache).writeBothDirections("BTC", "KRW", new BigDecimal("106879900"));
    }

    @Test
    @DisplayName("암호화폐가 quote 면 큰 방향을 조회해 로컬에서 역수를 취한다")
    void invertsWhenCryptoIsQuote() {
        // KRW→BTC 를 공급자에게 직접 물으면 양자화로 유효숫자가 날아간다.
        // 항상 암호화폐를 base 로 조회하고 역수는 BigDecimal 로 계산한다.
        mockServer.expect(requestTo("/api/v3/simple/price?ids=bitcoin&vs_currencies=krw"))
                .andRespond(withSuccess("{\"bitcoin\":{\"krw\":106879900}}", MediaType.APPLICATION_JSON));

        ExchangeRate result = adapter.getRate("KRW", "BTC");

        assertThat(result.rate()).isGreaterThan(new BigDecimal("9.35e-9"));
        assertThat(result.rate()).isLessThan(new BigDecimal("9.36e-9"));
        mockServer.verify();
    }

    @Test
    @DisplayName("다중 조회는 여러 자산을 1회 호출로 묶는다")
    void batchFetchesInSingleCall() {
        mockServer.expect(requestTo("/api/v3/simple/price?ids=bitcoin,ethereum&vs_currencies=krw"))
                .andRespond(withSuccess(
                        "{\"bitcoin\":{\"krw\":106879900},\"ethereum\":{\"krw\":3388999}}",
                        MediaType.APPLICATION_JSON));

        Map<String, BigDecimal> rates = adapter.getRates(List.of("BTC", "ETH"), "KRW");

        assertThat(rates).hasSize(2);
        assertThat(rates.get("BTC")).isEqualByComparingTo("106879900");
        assertThat(rates.get("ETH")).isEqualByComparingTo("3388999");
        verify(cache).writeBothDirections("BTC", "KRW", new BigDecimal("106879900"));
        verify(cache).writeBothDirections("ETH", "KRW", new BigDecimal("3388999"));
        mockServer.verify();
    }

    @Test
    @DisplayName("다중 조회에서 응답에 없는 자산은 결과에서 제외한다")
    void batchSkipsMissingAssets() {
        mockServer.expect(requestTo("/api/v3/simple/price?ids=bitcoin,ethereum&vs_currencies=krw"))
                .andRespond(withSuccess("{\"bitcoin\":{\"krw\":106879900}}", MediaType.APPLICATION_JSON));

        Map<String, BigDecimal> rates = adapter.getRates(List.of("BTC", "ETH"), "KRW");

        assertThat(rates).containsOnlyKeys("BTC");
    }

    @Test
    @DisplayName("coin id 가 설정되지 않은 심볼만 넘기면 호출하지 않고 빈 결과를 준다")
    void batchWithNoKnownIdsDoesNotCallApi() {
        assertThat(adapter.getRates(List.of("UNKNOWNCOIN"), "KRW")).isEmpty();
        mockServer.verify();
    }

    @Test
    @DisplayName("모르는 표시 통화는 빈 객체로 오므로 영구 오류로 분류한다")
    void unknownQuoteCurrencyBecomesPermanentFailure() {
        // CoinGecko 는 모르는 vs_currencies 를 오류가 아니라 빈 객체로 응답한다.
        mockServer.expect(requestTo("/api/v3/simple/price?ids=bitcoin&vs_currencies=zzz"))
                .andRespond(withSuccess("{\"bitcoin\":{}}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> adapter.getRate("BTC", "ZZZ"))
                .isInstanceOf(UnsupportedAssetCodeException.class);
    }

    @Test
    @DisplayName("coin id 가 설정되지 않은 심볼은 영구 오류로 거부한다")
    void unmappedSymbolBecomesPermanentFailure() {
        assertThatThrownBy(() -> adapter.getRate("SOL", "KRW"))
                .isInstanceOf(UnsupportedAssetCodeException.class);
        mockServer.verify();
    }

    @Test
    @DisplayName("캐시 히트면 API 를 호출하지 않는다")
    void cacheHitSkipsApiCall() {
        when(cache.lookup("BTC", "KRW"))
                .thenReturn(Optional.of(new ExchangeRate(new BigDecimal("106000000"), false)));

        assertThat(adapter.getRate("BTC", "KRW").rate()).isEqualByComparingTo("106000000");
        mockServer.verify();
    }

    @Test
    @DisplayName("데모 키를 설정하면 전용 헤더로 전달한다")
    void sendsDemoKeyHeader() {
        CryptoAssetProperties props = new CryptoAssetProperties();
        props.setSymbolIds(Map.of("BTC", "bitcoin"));

        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer keyedServer = MockRestServiceServer.bindTo(builder).build();
        CoinGeckoAdapter keyedAdapter = new CoinGeckoAdapter(builder.build(), cache, props, "demo-key");

        keyedServer.expect(requestTo("/api/v3/simple/price?ids=bitcoin&vs_currencies=krw"))
                .andExpect(header("x-cg-demo-api-key", "demo-key"))
                .andRespond(withSuccess("{\"bitcoin\":{\"krw\":1}}", MediaType.APPLICATION_JSON));

        keyedAdapter.getRate("BTC", "KRW");
        keyedServer.verify();
    }

    @Test
    @DisplayName("폴백은 공용 열화 정책에 위임한다")
    void fallbackDelegatesToSharedPolicy() {
        RuntimeException cause = new RuntimeException("CoinGecko down");
        when(cache.degradeOrThrow("BTC", "KRW", cause))
                .thenReturn(new ExchangeRate(new BigDecimal("100000000"), true));

        assertThat(adapter.fallbackRate("BTC", "KRW", cause).isStale()).isTrue();
    }

    @Test
    @DisplayName("심볼 매핑은 대소문자를 구분하지 않는다")
    void symbolLookupIsCaseInsensitive() {
        CryptoAssetProperties props = new CryptoAssetProperties();
        props.setSymbolIds(Map.of("BTC", "bitcoin"));

        assertThat(props.isCrypto("btc")).isTrue();
        assertThat(props.coinIdOf("btc")).isEqualTo("bitcoin");
        assertThat(props.isCrypto(null)).isFalse();
        assertThat(props.coinIdOf(null)).isNull();
        assertThat(props.isCrypto("AAPL")).isFalse();
    }
}
