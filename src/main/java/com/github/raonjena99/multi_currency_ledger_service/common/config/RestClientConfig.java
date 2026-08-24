package com.github.raonjena99.multi_currency_ledger_service.common.config;

import java.net.http.HttpClient;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import lombok.extern.slf4j.Slf4j;

/**
 * 외부 API별 전용 {@link RestClient} 를 등록합니다.
 *
 * <p><b>base URL 이 이 클래스의 존재 이유입니다.</b> 이전 구현은 {@code builder.build()} 만 호출해
 * base URL 이 없는 클라이언트 하나를 공유했습니다. 그런데 어댑터들은 {@code "/latest"} 처럼 루트
 * 상대 경로로 호출하므로, 모든 외부 호출이
 * {@code IllegalArgumentException: URI with undefined scheme} 로 실패했습니다. 그 예외는
 * {@code @Retry} 의 {@code retryExceptions} 에 없어 재시도되지 않고, 서킷 브레이커 폴백이 빈
 * 캐시를 만나 결국 <b>모든 거래가 실패</b>했습니다.
 *
 * <p>테스트가 이 결함을 잡지 못한 이유도 함께 기록해 둡니다. 어댑터 단위 테스트는
 * {@code MockRestServiceServer.bindTo(builder)} 로 자체 클라이언트를 만드는데, 이 목 서버는
 * <b>URI 해석 이전에</b> 요청을 가로챕니다. 통합 테스트는 공급자를 목으로 대체합니다.
 * 그래서 base URL 누락이 양쪽 모두에서 보이지 않았습니다.
 *
 * <p><b>타임아웃도 함께 지정합니다.</b> Resilience4j 의 {@code slowCallDurationThreshold} 는 느린
 * 호출을 <b>측정</b>할 뿐 끊지 않습니다. 소켓 타임아웃이 없으면 응답하지 않는 업스트림이 요청
 * 스레드를 무한정 붙잡습니다.
 */
@Slf4j
@Configuration
public class RestClientConfig {

    /**
     * 법정화폐 환율 조회용 클라이언트 (fxratesapi.com).
     *
     * @param builder        Spring Boot 가 제공하는 빌더
     * @param baseUrl        업스트림 base URL
     * @param connectTimeout 연결 타임아웃
     * @param readTimeout    응답 타임아웃
     * @return 전용 RestClient
     */
    @Bean
    RestClient fxRatesRestClient(
            RestClient.Builder builder,
            @Value("${ledger.external.exchange-rate.base-url}") String baseUrl,
            @Value("${ledger.external.exchange-rate.connect-timeout:2s}") Duration connectTimeout,
            @Value("${ledger.external.exchange-rate.read-timeout:3s}") Duration readTimeout) {
        return builder.clone()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory(connectTimeout, readTimeout))
                .build();
    }

    /**
     * 암호화폐 시세 조회용 클라이언트 (CoinGecko).
     *
     * @param builder        Spring Boot 가 제공하는 빌더
     * @param baseUrl        업스트림 base URL
     * @param connectTimeout 연결 타임아웃
     * @param readTimeout    응답 타임아웃
     * @return 전용 RestClient
     */
    @Bean
    RestClient coinGeckoRestClient(
            RestClient.Builder builder,
            @Value("${ledger.external.crypto.base-url}") String baseUrl,
            @Value("${ledger.external.crypto.connect-timeout:2s}") Duration connectTimeout,
            @Value("${ledger.external.crypto.read-timeout:3s}") Duration readTimeout) {
        return builder.clone()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory(connectTimeout, readTimeout))
                .build();
    }

    /**
     * PG 정산망 조회용 클라이언트.
     *
     * <p>개인 개발자는 PG 정산망에 접속할 수 없으므로 base URL 기본값이 없습니다. 미설정 상태에서는
     * 경고를 남기고 base URL 없이 빈을 만듭니다. 기동은 막지 않되(대사 배치는 적재된 데이터가
     * 없으므로 아무 일도 하지 않습니다), 호출이 발생하면 즉시 실패해 미설정 상태가 드러납니다.
     *
     * @param builder        Spring Boot 가 제공하는 빌더
     * @param baseUrl        업스트림 base URL. 비어 있으면 미설정으로 간주
     * @param connectTimeout 연결 타임아웃
     * @param readTimeout    응답 타임아웃
     * @return 전용 RestClient
     */
    @Bean
    RestClient pgRestClient(
            RestClient.Builder builder,
            @Value("${ledger.external.pg.base-url:}") String baseUrl,
            @Value("${ledger.external.pg.connect-timeout:2s}") Duration connectTimeout,
            @Value("${ledger.external.pg.read-timeout:5s}") Duration readTimeout) {

        RestClient.Builder configured = builder.clone()
                .requestFactory(requestFactory(connectTimeout, readTimeout));

        if (!StringUtils.hasText(baseUrl)) {
            log.warn("PG 정산망 base URL(ledger.external.pg.base-url)이 설정되지 않았습니다. "
                    + "정산 데이터 적재는 사용할 수 없습니다.");
            return configured.build();
        }
        return configured.baseUrl(baseUrl).build();
    }

    /**
     * 연결·응답 타임아웃이 적용된 요청 팩토리를 만듭니다.
     */
    private static ClientHttpRequestFactory requestFactory(Duration connectTimeout, Duration readTimeout) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(connectTimeout).build());
        factory.setReadTimeout(readTimeout);
        return factory;
    }
}
