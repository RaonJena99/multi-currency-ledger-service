package com.github.raonjena99.multi_currency_ledger_service.common.infrastructure.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.math.BigDecimal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.github.raonjena99.multi_currency_ledger_service.common.port.ExchangeRateProvider.ExchangeRate;

@ExtendWith(MockitoExtension.class)
class LiveExchangeRateAdapterTest {

    private LiveExchangeRateAdapter adapter;
    private MockRestServiceServer mockServer;

    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOperations;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        mockServer = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.build();
        
        adapter = new LiveExchangeRateAdapter(restClient, redisTemplate);
    }

    @Test
    @DisplayName("getExchangeRate - API 정상 응답")
    void testGetExchangeRate_Success() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        
        mockServer.expect(requestTo("/api/v1/market-data/rates?base=BTC&target=KRW"))
                .andRespond(withSuccess("50000000", MediaType.APPLICATION_JSON));

        ExchangeRate result = adapter.getExchangeRate("BTC", "KRW");

        assertThat(result.rate()).isEqualByComparingTo(new BigDecimal("50000000"));
        assertThat(result.isStale()).isFalse();
    }

    @Test
    @DisplayName("getExchangeRate - API 500 에러 시 폴백되어 Redis에서 캐시 데이터 가져옴")
    void testGetExchangeRate_FallbackToRedis() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("ledger:exchange-rate:ETH:KRW")).thenReturn("3000000");

        mockServer.expect(requestTo("/api/v1/market-data/rates?base=ETH&target=KRW"))
                .andRespond(withServerError());

        ExchangeRate result = adapter.fallbackExchangeRate("ETH", "KRW", new RuntimeException("500 Error"));

        assertThat(result.rate()).isEqualByComparingTo(new BigDecimal("3000000"));
        assertThat(result.isStale()).isTrue();
    }
}
