package com.github.raonjena99.multi_currency_ledger_service.portfolio.application.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record PortfolioSummaryResponse(
        UUID accountId,
        BigDecimal totalAssetValue,
        BigDecimal totalUnrealizedPnl,
        boolean isStaleData, 
        List<AssetDetailDto> assets
) {
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