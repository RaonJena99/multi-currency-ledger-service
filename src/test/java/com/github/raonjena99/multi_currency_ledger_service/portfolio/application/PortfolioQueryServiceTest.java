package com.github.raonjena99.multi_currency_ledger_service.portfolio.application;

import static org.assertj.core.api.Assertions.assertThat;
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

import com.github.raonjena99.multi_currency_ledger_service.common.port.ExchangeRateProvider;
import com.github.raonjena99.multi_currency_ledger_service.portfolio.application.dto.PortfolioSummaryResponse;
import com.github.raonjena99.multi_currency_ledger_service.portfolio.domain.CurrentPortfolio;
import com.github.raonjena99.multi_currency_ledger_service.portfolio.infrastructure.PortfolioQueryRepository;

@ExtendWith(MockitoExtension.class)
@DisplayName("애플리케이션 단위 테스트: PortfolioQueryService (CQRS 조회 및 집계 로직 검증)")
class PortfolioQueryServiceTest {

    @Mock
    private PortfolioQueryRepository portfolioQueryRepository;

    @Mock
    private ExchangeRateProvider exchangeRateProvider;

    @InjectMocks
    private PortfolioQueryService portfolioQueryService;

    @Test
    @DisplayName("사용자의 여러 자산을 조회하여 총 자산 가치(Total Asset Value)와 총 손익(Unrealized PnL)을 완벽히 집계한다.")
    void aggregate_portfolio_summary() {
        // given
        UUID accountId = UUID.randomUUID();

        CurrentPortfolio btc = mock(CurrentPortfolio.class);
        when(btc.getAssetCode()).thenReturn("BTC");
        when(btc.getTotalQuantity()).thenReturn(new BigDecimal("2"));
        when(btc.getAvgUnitPrice()).thenReturn(new BigDecimal("50000000"));

        CurrentPortfolio eth = mock(CurrentPortfolio.class);
        when(eth.getAssetCode()).thenReturn("ETH");
        when(eth.getTotalQuantity()).thenReturn(new BigDecimal("10"));
        when(eth.getAvgUnitPrice()).thenReturn(new BigDecimal("4000000"));

        when(portfolioQueryRepository.findAllByAccountId(accountId)).thenReturn(List.of(btc, eth));

        when(exchangeRateProvider.getExchangeRate("BTC", "KRW"))
                .thenReturn(new ExchangeRateProvider.ExchangeRate(new BigDecimal("80000000"), false));
        when(exchangeRateProvider.getExchangeRate("ETH", "KRW"))
                .thenReturn(new ExchangeRateProvider.ExchangeRate(new BigDecimal("3000000"), false));

        // when
        PortfolioSummaryResponse response = portfolioQueryService.getPortfolioSummary(accountId);

        // then
        // 총 자산 가치 검증 (2 * 80m + 10 * 3m = 160m + 30m = 190m)
        assertThat(response.totalAssetValue()).isEqualByComparingTo("190000000");

        // 총 미실현 손익 검증 (2*(80m-50m) + 10*(3m-4m) = 60m - 10m = 50m)
        assertThat(response.totalUnrealizedPnl()).isEqualByComparingTo("50000000");

        // 자산 세부 리스트 개수 검증
        assertThat(response.assets()).hasSize(2);
    }

    @Test
    @DisplayName("자산 가격 조회 중 staleRate가 하나라도 있으면 finalStaleFlag가 true가 된다")
    void aggregate_portfolio_summary_with_stale() {
        UUID accountId = UUID.randomUUID();

        CurrentPortfolio btc = mock(CurrentPortfolio.class);
        when(btc.getAssetCode()).thenReturn("BTC");
        when(btc.getTotalQuantity()).thenReturn(new BigDecimal("2"));
        when(btc.getAvgUnitPrice()).thenReturn(new BigDecimal("50000000"));

        when(portfolioQueryRepository.findAllByAccountId(accountId)).thenReturn(List.of(btc));

        when(exchangeRateProvider.getExchangeRate("BTC", "KRW"))
                .thenReturn(new ExchangeRateProvider.ExchangeRate(new BigDecimal("80000000"), true));

        PortfolioSummaryResponse response = portfolioQueryService.getPortfolioSummary(accountId);

        assertThat(response.isStaleData()).isTrue();
    }
}
