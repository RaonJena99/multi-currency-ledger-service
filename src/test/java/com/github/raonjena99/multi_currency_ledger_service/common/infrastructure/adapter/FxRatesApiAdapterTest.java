package com.github.raonjena99.multi_currency_ledger_service.common.infrastructure.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.math.BigDecimal;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.github.raonjena99.multi_currency_ledger_service.common.exception.MarketDataUnavailableException;
import com.github.raonjena99.multi_currency_ledger_service.common.exception.UnsupportedAssetCodeException;
import com.github.raonjena99.multi_currency_ledger_service.common.port.ExchangeRateProvider.ExchangeRate;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("fxratesapi 어댑터: 실제 응답 계약과 정밀도 가드")
class FxRatesApiAdapterTest {

    /** 실측한 fxratesapi 성공 응답 형태. */
    private static final String BTC_KRW_BODY = """
            {"success":true,"terms":"https://fxratesapi.com/legal/terms-conditions",
             "timestamp":1787559300,"date":"2026-08-24T08:15:00.000Z",
             "base":"BTC","rates":{"KRW":106995446.66022733}}
            """;

    /** 실측한 fxratesapi 실패 응답 형태 (HTTP 400). */
    private static final String INVALID_CURRENCY_BODY = """
            {"success":false,"error":"invalid_currencies",
             "description":"The currencies parameter is not valid."}
            """;

    @Mock private ExchangeRateCache cache;

    private MockRestServiceServer mockServer;
    private FxRatesApiAdapter adapter;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        mockServer = MockRestServiceServer.bindTo(builder).build();
        adapter = new FxRatesApiAdapter(builder.build(), cache, "", new BigDecimal("0.0001"));
        when(cache.lookup(anyString(), anyString())).thenReturn(Optional.empty());
    }

    @Test
    @DisplayName("정상 조회 - rates 맵에서 해당 통화 값을 꺼내 반환하고 양방향 캐시에 기록한다")
    void fetchesRateFromRatesMap() {
        mockServer.expect(requestTo("/latest?base=BTC&currencies=KRW"))
                .andRespond(withSuccess(BTC_KRW_BODY, MediaType.APPLICATION_JSON));

        ExchangeRate result = adapter.getRate("BTC", "KRW");

        assertThat(result.rate()).isEqualByComparingTo("106995446.66022733");
        assertThat(result.isStale()).isFalse();
        verify(cache).writeBothDirections("BTC", "KRW", new BigDecimal("106995446.66022733"));
        mockServer.verify();
    }

    @Test
    @DisplayName("정밀도 가드 - 하한 미만이면 역방향을 조회해 로컬에서 역수를 계산한다")
    void invertsWhenRateBelowPrecisionFloor() {
        // 공급자는 KRW→BTC 를 9e-9 로만 준다(유효숫자 1자리, 참값 9.3461e-9). 그대로 쓰면 오차 3.7%.
        mockServer.expect(requestTo("/latest?base=KRW&currencies=BTC"))
                .andRespond(withSuccess(
                        "{\"success\":true,\"base\":\"KRW\",\"rates\":{\"BTC\":9e-9}}",
                        MediaType.APPLICATION_JSON));
        mockServer.expect(requestTo("/latest?base=BTC&currencies=KRW"))
                .andRespond(withSuccess(BTC_KRW_BODY, MediaType.APPLICATION_JSON));

        ExchangeRate result = adapter.getRate("KRW", "BTC");

        // 1 / 106995446.66022733 = 9.3462...e-9 (양자화된 9e-9 보다 정밀하다)
        assertThat(result.rate()).isGreaterThan(new BigDecimal("9.3e-9"));
        assertThat(result.rate()).isLessThan(new BigDecimal("9.4e-9"));
        mockServer.verify();
    }

    @Test
    @DisplayName("정밀도가 충분하면 역방향을 조회하지 않는다 - 좁은 쿼터를 낭비하지 않는다")
    void doesNotInvertWhenPrecisionSufficient() {
        mockServer.expect(requestTo("/latest?base=USD&currencies=EUR"))
                .andRespond(withSuccess(
                        "{\"success\":true,\"base\":\"USD\",\"rates\":{\"EUR\":0.8573460912}}",
                        MediaType.APPLICATION_JSON));

        ExchangeRate result = adapter.getRate("USD", "EUR");

        assertThat(result.rate()).isEqualByComparingTo("0.8573460912");
        // 두 번째 호출을 기대하지 않았으므로 verify 가 초과 호출을 잡아낸다.
        mockServer.verify();
    }

    @Test
    @DisplayName("HTTP 400 은 지원하지 않는 통화 코드로 분류해 재시도·서킷 집계에서 제외시킨다")
    void badRequestBecomesPermanentFailure() {
        mockServer.expect(requestTo("/latest?base=USD&currencies=AAPL"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .body(INVALID_CURRENCY_BODY)
                        .contentType(MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> adapter.getRate("USD", "AAPL"))
                .isInstanceOf(UnsupportedAssetCodeException.class);
    }

    @Test
    @DisplayName("본문에 success=false 가 오면 영구 오류로 본다")
    void successFalseBecomesPermanentFailure() {
        mockServer.expect(requestTo("/latest?base=USD&currencies=NOPE"))
                .andRespond(withSuccess(INVALID_CURRENCY_BODY, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> adapter.getRate("USD", "NOPE"))
                .isInstanceOf(UnsupportedAssetCodeException.class);
    }

    @Test
    @DisplayName("캐시 히트면 API 를 호출하지 않는다")
    void cacheHitSkipsApiCall() {
        when(cache.lookup("USD", "KRW"))
                .thenReturn(Optional.of(new ExchangeRate(new BigDecimal("1384.72"), false)));

        ExchangeRate result = adapter.getRate("USD", "KRW");

        assertThat(result.rate()).isEqualByComparingTo("1384.72");
        // 아무 요청도 기대하지 않았다.
        mockServer.verify();
    }

    @Test
    @DisplayName("응답 rates 에 요청한 통화가 없으면 열화 정책으로 넘긴다")
    void missingRateDelegatesToDegradation() {
        mockServer.expect(requestTo("/latest?base=USD&currencies=KRW"))
                .andRespond(withSuccess(
                        "{\"success\":true,\"base\":\"USD\",\"rates\":{}}", MediaType.APPLICATION_JSON));
        mockServer.expect(requestTo("/latest?base=KRW&currencies=USD"))
                .andRespond(withSuccess(
                        "{\"success\":true,\"base\":\"KRW\",\"rates\":{}}", MediaType.APPLICATION_JSON));
        when(cache.degradeOrThrow(anyString(), anyString(), any(Throwable.class)))
                .thenThrow(new MarketDataUnavailableException("캐시 고갈"));

        assertThatThrownBy(() -> adapter.getRate("USD", "KRW"))
                .isInstanceOf(MarketDataUnavailableException.class);
    }

    @Test
    @DisplayName("폴백은 공용 열화 정책에 위임한다")
    void fallbackDelegatesToSharedPolicy() {
        RuntimeException cause = new RuntimeException("API down");
        when(cache.degradeOrThrow("ETH", "KRW", cause))
                .thenReturn(new ExchangeRate(new BigDecimal("3000000"), true));

        ExchangeRate result = adapter.fallbackRate("ETH", "KRW", cause);

        assertThat(result.rate()).isEqualByComparingTo("3000000");
        assertThat(result.isStale()).isTrue();
        verify(cache).degradeOrThrow("ETH", "KRW", cause);
    }

    @Test
    @DisplayName("5xx 는 일시적 장애이므로 영구 오류로 분류하지 않는다")
    void serverErrorIsNotPermanent() {
        mockServer.expect(requestTo("/latest?base=USD&currencies=KRW"))
                .andRespond(withServerError());

        assertThatThrownBy(() -> adapter.getRate("USD", "KRW"))
                .isNotInstanceOf(UnsupportedAssetCodeException.class);
    }

    @Test
    @DisplayName("API 키를 설정하면 질의 파라미터로 전달한다")
    void appendsApiKeyWhenConfigured() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer keyedServer = MockRestServiceServer.bindTo(builder).build();
        FxRatesApiAdapter keyedAdapter =
                new FxRatesApiAdapter(builder.build(), cache, "secret-key", new BigDecimal("0.0001"));

        keyedServer.expect(requestTo("/latest?base=USD&currencies=KRW&api_key=secret-key"))
                .andRespond(withSuccess(
                        "{\"success\":true,\"base\":\"USD\",\"rates\":{\"KRW\":1384.72}}",
                        MediaType.APPLICATION_JSON));

        assertThat(keyedAdapter.getRate("USD", "KRW").rate()).isEqualByComparingTo("1384.72");
        keyedServer.verify();
    }
}
