package com.github.raonjena99.multi_currency_ledger_service.portfolio.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CurrentPortfolioTest {

    @Test
    @DisplayName("포트폴리오 자산의 수량과 현재 시장가를 기반으로 정확한 평가 금액(Valuation)을 계산한다.")
    void calculateValuation_ReturnsCorrectAmount() {
        CurrentPortfolio portfolio = CurrentPortfolio.builder()
                .accountId("ACC-001")
                .assetType("STOCK")
                .assetId("AAPL")
                .quantity(BigDecimal.valueOf(10))
                .averageCost(BigDecimal.valueOf(150)) // 총 매입가 1500
                .build();

        // 시장가가 180으로 올랐을 때의 평가 (180 * 10 = 1800)
        BigDecimal currentMarketPrice = BigDecimal.valueOf(180);
        BigDecimal valuation = portfolio.calculateCurrentValuation(currentMarketPrice);

        assertThat(valuation).isEqualByComparingTo("1800");
    }

    @Test
    @DisplayName("시장가 대비 매입 평균가를 비교하여 평가 손익(PnL)을 정확히 산출한다.")
    void calculatePnL_ReturnsCorrectProfitOrLoss() {
        CurrentPortfolio portfolio = CurrentPortfolio.builder()
                .accountId("ACC-001")
                .assetType("CRYPTO")
                .assetId("BTC")
                .quantity(BigDecimal.valueOf(2))
                .averageCost(BigDecimal.valueOf(50000)) // 매입가 100,000
                .build();

        // 시장가 45,000으로 하락 시 (손실 -10,000)
        BigDecimal pnl = portfolio.calculatePnL(BigDecimal.valueOf(45000));

        assertThat(pnl).isEqualByComparingTo("-10000");
    }
}