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

import com.github.raonjena99.multi_currency_ledger_service.portfolio.application.dto.PortfolioSummaryResponse;
import com.github.raonjena99.multi_currency_ledger_service.portfolio.domain.CurrentPortfolio;
import com.github.raonjena99.multi_currency_ledger_service.portfolio.infrastructure.PortfolioQueryRepository;

@ExtendWith(MockitoExtension.class)
@DisplayName("애플리케이션 단위 테스트: PortfolioQueryService (CQRS 조회 및 집계 로직 검증)")
class PortfolioQueryServiceTest {

    @Mock
    private PortfolioQueryRepository portfolioQueryRepository;

    @InjectMocks
    private PortfolioQueryService portfolioQueryService;

    @Test
    @DisplayName("사용자의 여러 자산을 조회하여 총 자산 가치(Total Asset Value)와 총 손익(Unrealized PnL)을 완벽히 집계한다.")
    void aggregate_portfolio_summary() {
        // given
        UUID accountId = UUID.randomUUID();

        // when().thenReturn() 구조
        CurrentPortfolio btc = mock(CurrentPortfolio.class);
        when(btc.getAssetCode()).thenReturn("BTC");
        when(btc.getTotalQuantity()).thenReturn(new BigDecimal("2"));
        when(btc.getAvgUnitPrice()).thenReturn(new BigDecimal("50000000"));
        when(btc.getCurrentMarketPrice()).thenReturn(new BigDecimal("80000000")); 
        when(btc.getUnrealizedPnl()).thenReturn(new BigDecimal("60000000")); 

        CurrentPortfolio eth = mock(CurrentPortfolio.class);
        when(eth.getAssetCode()).thenReturn("ETH");
        when(eth.getTotalQuantity()).thenReturn(new BigDecimal("10"));
        when(eth.getAvgUnitPrice()).thenReturn(new BigDecimal("4000000"));
        when(eth.getCurrentMarketPrice()).thenReturn(new BigDecimal("3000000")); 
        when(eth.getUnrealizedPnl()).thenReturn(new BigDecimal("-10000000")); 

        when(portfolioQueryRepository.findAllByAccountId(accountId)).thenReturn(List.of(btc, eth));

        // when
        PortfolioSummaryResponse response = portfolioQueryService.getPortfolioSummary(accountId);

        // then
        // 총 자산 가치 검증
        assertThat(response.totalAssetValue()).isEqualByComparingTo("190000000");

        // 총 미실현 손익 검증
        assertThat(response.totalUnrealizedPnl()).isEqualByComparingTo("50000000");

        // 자산 세부 리스트 개수 검증
        assertThat(response.assets()).hasSize(2);
    }
}