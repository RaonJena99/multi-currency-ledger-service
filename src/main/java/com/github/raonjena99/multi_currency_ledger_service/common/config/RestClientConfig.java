package com.github.raonjena99.multi_currency_ledger_service.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * 외부 API 호출을 위한 RestClient(REST 클라이언트) 설정을 담당하는 RestClientConfig 클래스입니다.
 */
@Configuration
public class RestClientConfig {

    /**
     * 애플리케이션에서 공통으로 사용할 커스텀 RestClient(REST 클라이언트)를 생성하여 빈으로 등록합니다.
     *
     * @param builder Spring Boot가 제공하는 RestClient.Builder 인스턴스
     * @return 생성된 RestClient 인스턴스
     */
    @Bean
    public RestClient customRestClient(RestClient.Builder builder) {
        return builder.build();
    }
}
