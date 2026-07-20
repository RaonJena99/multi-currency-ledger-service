package com.github.raonjena99.multi_currency_ledger_service.portfolio.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.github.raonjena99.multi_currency_ledger_service.account.AccountApi;
import com.github.raonjena99.multi_currency_ledger_service.common.port.ExchangeRateProvider;
import com.github.raonjena99.multi_currency_ledger_service.portfolio.application.dto.PortfolioSummaryResponse;
import com.github.raonjena99.multi_currency_ledger_service.portfolio.domain.CurrentPortfolio;
import com.github.raonjena99.multi_currency_ledger_service.portfolio.infrastructure.PortfolioQueryRepository;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

@ExtendWith(MockitoExtension.class)
@DisplayName("애플리케이션 단위 테스트: PortfolioQueryService")
class PortfolioQueryServiceTest {

    @Mock
    private PortfolioQueryRepository portfolioQueryRepository;

    @Mock
    private AccountApi accountApi;

    @Mock
    private ExchangeRateProvider exchangeRateProvider;

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    @Mock
    private ValueOperations<String, Object> valueOperations;

    @InjectMocks
    private PortfolioQueryService portfolioQueryService;

    @Test
    @DisplayName("사용자의 여러 자산을 조회하여 총 자산 가치와 총 손익을 완벽히 집계한다.")
    void aggregate_portfolio_summary() {
        // given
        UUID accountId = UUID.randomUUID();
        when(accountApi.getBaseCurrency(accountId)).thenReturn("KRW");

        CurrentPortfolio btc = mock(CurrentPortfolio.class);
        when(btc.getAssetCode()).thenReturn("BTC");
        when(btc.getQuoteCurrency()).thenReturn("KRW");
        when(btc.getTotalQuantity()).thenReturn(new BigDecimal("2"));
        when(btc.getAvgUnitPrice()).thenReturn(new BigDecimal("50000000"));

        CurrentPortfolio eth = mock(CurrentPortfolio.class);
        when(eth.getAssetCode()).thenReturn("ETH");
        when(eth.getQuoteCurrency()).thenReturn("KRW");
        when(eth.getTotalQuantity()).thenReturn(new BigDecimal("10"));
        when(eth.getAvgUnitPrice()).thenReturn(new BigDecimal("4000000"));

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("portfolio:account:" + accountId)).thenReturn(null);
        when(valueOperations.setIfAbsent(eq("lock:portfolio:" + accountId), eq("LOCKED"), any())).thenReturn(true);
        when(portfolioQueryRepository.findAllByAccountId(accountId)).thenReturn(List.of(btc, eth));

        java.util.Map<String, ExchangeRateProvider.ExchangeRate> mockRates = new java.util.HashMap<>();
        mockRates.put("BTC", new ExchangeRateProvider.ExchangeRate(new BigDecimal("80000000"), false));
        mockRates.put("ETH", new ExchangeRateProvider.ExchangeRate(new BigDecimal("3000000"), false));
        mockRates.put("KRW", new ExchangeRateProvider.ExchangeRate(BigDecimal.ONE, false));
        
        when(exchangeRateProvider.getExchangeRates(any(), eq("KRW"))).thenReturn(mockRates);

        // when
        PortfolioSummaryResponse response = portfolioQueryService.getPortfolioSummary(accountId);

        // then
        assertThat(response.totalAssetValue()).isEqualByComparingTo("190000000");
        assertThat(response.totalUnrealizedPnl()).isEqualByComparingTo("50000000");
        assertThat(response.assets()).hasSize(2);
    }

    @Test
    @DisplayName("자산 가격 조회 중 staleRate가 하나라도 있으면 finalStaleFlag가 true가 된다")
    void aggregate_portfolio_summary_with_stale() {
        UUID accountId = UUID.randomUUID();
        when(accountApi.getBaseCurrency(accountId)).thenReturn("KRW");

        CurrentPortfolio btc = mock(CurrentPortfolio.class);
        when(btc.getAssetCode()).thenReturn("BTC");
        when(btc.getQuoteCurrency()).thenReturn("KRW");
        when(btc.getTotalQuantity()).thenReturn(new BigDecimal("2"));
        when(btc.getAvgUnitPrice()).thenReturn(new BigDecimal("50000000"));

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("portfolio:account:" + accountId)).thenReturn(null);
        when(valueOperations.setIfAbsent(eq("lock:portfolio:" + accountId), eq("LOCKED"), any())).thenReturn(true);
        when(portfolioQueryRepository.findAllByAccountId(accountId)).thenReturn(List.of(btc));

        java.util.Map<String, ExchangeRateProvider.ExchangeRate> mockRates = new java.util.HashMap<>();
        mockRates.put("BTC", new ExchangeRateProvider.ExchangeRate(new BigDecimal("80000000"), true));
        mockRates.put("KRW", new ExchangeRateProvider.ExchangeRate(BigDecimal.ONE, false));
        
        when(exchangeRateProvider.getExchangeRates(any(), eq("KRW"))).thenReturn(mockRates);

        PortfolioSummaryResponse response = portfolioQueryService.getPortfolioSummary(accountId);

        assertThat(response.isStaleData()).isTrue();
    }
}
