package com.github.raonjena99.multi_currency_ledger_service.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class RestClientConfig {

    @Bean
    public RestClient customRestClient(RestClient.Builder builder) {
        return builder.build();
    }
}
