package com.github.raonjena99.multi_currency_ledger_service.common.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.ResourceAccessException;

/**
 * 회귀 테스트: 외부 API 클라이언트에 base URL 과 타임아웃이 반드시 설정되어야 합니다.
 *
 * <p>이전 구현은 base URL 없는 {@link RestClient} 하나를 공유했고, 어댑터들은
 * {@code "/latest"} 같은 루트 상대 경로로 호출했습니다. 그 조합은 런타임에
 * {@code IllegalArgumentException: URI with undefined scheme} 로 실패해 <b>모든 거래가 422</b> 로
 * 떨어졌습니다.
 *
 * <p>어댑터 단위 테스트는 이 결함을 구조적으로 잡을 수 없습니다.
 * {@code MockRestServiceServer} 가 URI 해석 <b>이전에</b> 요청을 가로채기 때문입니다.
 * 그래서 설정 계층을 직접 겨냥한 이 테스트가 따로 필요합니다.
 */
@DisplayName("회귀 테스트: 외부 API 클라이언트 base URL 과 타임아웃")
class RestClientConfigTest {

    private static final Duration TIMEOUT = Duration.ofMillis(500);

    private final RestClientConfig config = new RestClientConfig();

    @Test
    @DisplayName("base URL 이 없으면 상대 경로 호출이 URI 오류로 실패한다 - 회귀의 원인")
    void relativeUriFailsWithoutBaseUrl() {
        RestClient bare = RestClient.builder().build();

        assertThatThrownBy(() -> bare.get().uri("/latest?base=BTC&currencies=KRW")
                .retrieve().body(BigDecimal.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("scheme");
    }

    @Test
    @DisplayName("환율 클라이언트는 base URL 이 적용되어 상대 경로가 절대 URI 로 해석된다")
    void fxRatesClientResolvesRelativeUri() {
        RestClient client = config.fxRatesRestClient(
                RestClient.builder(), "http://127.0.0.1:1", TIMEOUT, TIMEOUT);

        // 127.0.0.1:1 은 즉시 연결 거부된다. 연결 단계까지 갔다는 것은 URI 해석이 끝났다는 뜻이다.
        // IllegalArgumentException 이 아니라 ResourceAccessException 이어야 한다.
        assertThatThrownBy(() -> client.get().uri("/latest?base=BTC&currencies=KRW")
                .retrieve().body(String.class))
                .isInstanceOf(ResourceAccessException.class);
    }

    @Test
    @DisplayName("암호화폐 클라이언트도 base URL 이 적용된다")
    void coinGeckoClientResolvesRelativeUri() {
        RestClient client = config.coinGeckoRestClient(
                RestClient.builder(), "http://127.0.0.1:1", TIMEOUT, TIMEOUT);

        assertThatThrownBy(() -> client.get().uri("/api/v3/simple/price?ids=bitcoin&vs_currencies=krw")
                .retrieve().body(String.class))
                .isInstanceOf(ResourceAccessException.class);
    }

    @Test
    @DisplayName("PG 클라이언트는 base URL 미설정에도 기동을 막지 않는다")
    void pgClientBuildsWithoutBaseUrl() {
        assertThat(config.pgRestClient(RestClient.builder(), "", TIMEOUT, TIMEOUT)).isNotNull();
        assertThat(config.pgRestClient(RestClient.builder(), "http://127.0.0.1:1", TIMEOUT, TIMEOUT))
                .isNotNull();
    }

    @Test
    @DisplayName("application.yaml 은 시세 API base URL 의 기본값을 제공해야 한다")
    void applicationYamlProvidesAbsoluteBaseUrlDefaults() throws Exception {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yaml"));

        assertThat(resolve(sources, "ledger.external.exchange-rate.base-url"))
                .as("환율 API base URL 기본값이 없으면 미설정 환경에서 모든 거래가 실패한다")
                .contains("https://api.fxratesapi.com");
        assertThat(resolve(sources, "ledger.external.crypto.base-url"))
                .contains("https://api.coingecko.com");
    }

    private static String resolve(List<PropertySource<?>> sources, String key) {
        return sources.stream()
                .filter(source -> source.containsProperty(key))
                .map(source -> String.valueOf(source.getProperty(key)))
                .findFirst()
                .orElseThrow(() -> new AssertionError("설정 키가 없습니다: " + key));
    }
}
