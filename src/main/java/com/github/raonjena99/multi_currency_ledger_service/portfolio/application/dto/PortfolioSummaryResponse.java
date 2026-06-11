package com.github.raonjena99.multi_currency_ledger_service.portfolio.application.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record PortfolioSummaryResponse(
    UUID accountId,
    BigDecimal totalAssetValue,      // 보유 자산 총 평가 금액 (현금 포함)
    BigDecimal totalUnrealizedPnl,   // 총 미실현 손익
    List<AssetDetailDto> assets      // 개별 자산 목록
) {
    public record AssetDetailDto(
        String assetCode,
        BigDecimal quantity,
        BigDecimal avgUnitPrice,
        BigDecimal currentMarketPrice,
        BigDecimal totalValue,       // 이 자산의 총 평가액 (quantity * currentMarketPrice)
        BigDecimal unrealizedPnl     // 미실현 손익
    ) {}
}
