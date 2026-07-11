package com.github.raonjena99.multi_currency_ledger_service.portfolio.application.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * 포트폴리오 요약 응답을 위한 PortfolioSummaryResponse(포트폴리오 요약) DTO 입니다.
 */
public record PortfolioSummaryResponse(
        UUID accountId,
        BigDecimal totalAssetValue,
        BigDecimal totalUnrealizedPnl,
        boolean isStaleData, 
        List<AssetDetailDto> assets
) {
    /**
     * 개별 자산의 상세 정보를 담은 AssetDetailDto(자산 상세) DTO 입니다.
     */
    public record AssetDetailDto(
            String assetCode,
            BigDecimal quantity,
            BigDecimal avgUnitPrice,
            BigDecimal currentMarketPrice,
            BigDecimal totalValue,
            BigDecimal unrealizedPnl,
            boolean isRateStale
    ) {}
}