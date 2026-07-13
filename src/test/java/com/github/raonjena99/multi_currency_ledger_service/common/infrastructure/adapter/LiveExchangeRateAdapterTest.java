package com.github.raonjena99.multi_currency_ledger_service.common.infrastructure.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

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

import com.github.raonjena99.multi_currency_ledger_service.common.exception.ArbitrageRiskException;
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
    @DisplayName("getExchangeRate - API 500 에러 시 폴백되어 Redis에서 5분 이내 캐시 데이터 가져옴")
    void testGetExchangeRate_FallbackToRedis() throws Exception {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        
        String validCache = "3000000|" + Instant.now().toEpochMilli();
        when(valueOperations.get("ledger:exchange-rate:ETH:KRW")).thenReturn(validCache);

        mockServer.expect(requestTo("/api/v1/market-data/rates?base=ETH&target=KRW"))
                .andRespond(withServerError());

        ExchangeRate result = adapter.fallbackExchangeRate("ETH", "KRW", new RuntimeException("500 Error"));

        assertThat(result.rate()).isEqualByComparingTo(new BigDecimal("3000000"));
        assertThat(result.isStale()).isTrue();
    }

    @Test
    @DisplayName("getExchangeRate - baseAsset과 targetAsset이 같으면 API 호출 없이 1 반환")
    void testGetExchangeRate_SameCurrency() {
        ExchangeRate result = adapter.getExchangeRate("BTC", "BTC");
        assertThat(result.rate()).isEqualByComparingTo(BigDecimal.ONE);
        assertThat(result.isStale()).isFalse();
    }

    @Test
    @DisplayName("getExchangeRate - Redis 쓰기 실패 시 예외를 무시하고 정상 응답 반환")
    void testGetExchangeRate_RedisWriteException() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        
        mockServer.expect(requestTo("/api/v1/market-data/rates?base=ETH&target=KRW"))
                .andRespond(withSuccess("3000000", MediaType.APPLICATION_JSON));
                
        // Mock Redis exception
        org.mockito.Mockito.doThrow(new RuntimeException("Redis down"))
            .when(valueOperations).set(eq("ledger:exchange-rate:ETH:KRW"), anyString(), eq(java.time.Duration.ofDays(1)));

        ExchangeRate result = adapter.getExchangeRate("ETH", "KRW");
        
        assertThat(result.rate()).isEqualByComparingTo(new BigDecimal("3000000"));
        assertThat(result.isStale()).isFalse();
    }

    @Test
    @DisplayName("fallbackExchangeRate - 캐시가 비어있으면 1 반환")
    void testFallbackExchangeRate_EmptyCache() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("ledger:exchange-rate:ETH:KRW")).thenReturn(null);

        ExchangeRate result = adapter.fallbackExchangeRate("ETH", "KRW", new RuntimeException("API down"));

        assertThat(result.rate()).isEqualByComparingTo(BigDecimal.ONE);
        assertThat(result.isStale()).isTrue();
    }

    @Test
    @DisplayName("fallbackExchangeRate - 5분이 지난 캐시는 ArbitrageRiskException을 던지며 차단된다")
    void testFallbackExchangeRate_StaleCache_ThrowsException() throws Exception {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        
        String staleCache = "3000000|" + Instant.now().minus(Duration.ofMinutes(10)).toEpochMilli();
        when(valueOperations.get("ledger:exchange-rate:ETH:KRW")).thenReturn(staleCache);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> {
            adapter.fallbackExchangeRate("ETH", "KRW", new RuntimeException("API down"));
        })
        .isInstanceOf(ArbitrageRiskException.class);
    }

    @Test
    @DisplayName("fallbackExchangeRate - Redis 읽기 실패 시 1 반환")
    void testFallbackExchangeRate_RedisReadException() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("ledger:exchange-rate:ETH:KRW")).thenThrow(new RuntimeException("Redis read down"));

        ExchangeRate result = adapter.fallbackExchangeRate("ETH", "KRW", new RuntimeException("API down"));

        assertThat(result.rate()).isEqualByComparingTo(BigDecimal.ONE);
        assertThat(result.isStale()).isTrue();
    }

    @Test
    @DisplayName("getExchangeRate - API 응답이 null일 때 Fallback 호출")
    void testGetExchangeRate_EmptyApiResponse() throws Exception {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        
        String validCache = "3000000|" + Instant.now().toEpochMilli();
        when(valueOperations.get("ledger:exchange-rate:ETH:KRW")).thenReturn(validCache);

        // Return empty body
        mockServer.expect(requestTo("/api/v1/market-data/rates?base=ETH&target=KRW"))
                .andRespond(withSuccess("", MediaType.APPLICATION_JSON));

        ExchangeRate result = adapter.getExchangeRate("ETH", "KRW");

        assertThat(result.rate()).isEqualByComparingTo(new BigDecimal("3000000"));
        assertThat(result.isStale()).isTrue();
    }
}
