package com.github.raonjena99.multi_currency_ledger_service.portfolio.domain;

import java.math.BigDecimal;

/**
 * 자산의 현재 가치와 미실현 손익(PnL)을 계산하고 담아두는 불변 값 객체(Value Object)입니다.
 */
public record PortfolioValuation(
    BigDecimal currentMarketPrice,
    BigDecimal totalValue,
    BigDecimal unrealizedPnl
) {
    /**
     * 현재 시장 가격과 환율을 기반으로 총 가치와 미실현 손익을 계산합니다.
     *
     * @param totalQuantity 보유 수량
     * @param avgUnitPrice 매입 평균 단가
     * @param currentMarketPrice 현재 시장 가격 (기준 통화 기준)
     * @param quoteToBaseExchangeRate 매입 통화(Quote Currency)를 기준 통화(Base Currency)로 변환하는 환율
     * @return 계산된 PortfolioValuation 객체
     */
    public static PortfolioValuation calculate(
            BigDecimal totalQuantity,
            BigDecimal avgUnitPrice,
            BigDecimal currentMarketPrice,
            BigDecimal quoteToBaseExchangeRate) {
        
        // 총 가치 = 현재 시장 가격 * 총 수량
        BigDecimal totalValue = currentMarketPrice.multiply(totalQuantity);
        
        // 기준 통화로 변환된 매입 평균 단가 = 매입 평균 단가 * (Quote -> Base 환율)
        BigDecimal convertedAvgUnitPrice = avgUnitPrice.multiply(quoteToBaseExchangeRate);
        
        // 미실현 손익 = (현재 시장 가격 - 변환된 매입 평균 단가) * 총 수량
        BigDecimal unrealizedPnl = currentMarketPrice.subtract(convertedAvgUnitPrice).multiply(totalQuantity);
        
        return new PortfolioValuation(currentMarketPrice, totalValue, unrealizedPnl);
    }
}
