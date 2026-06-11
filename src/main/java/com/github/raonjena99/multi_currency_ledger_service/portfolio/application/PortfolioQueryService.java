package com.github.raonjena99.multi_currency_ledger_service.portfolio.application;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.github.raonjena99.multi_currency_ledger_service.portfolio.application.dto.PortfolioSummaryResponse;
import com.github.raonjena99.multi_currency_ledger_service.portfolio.application.dto.PortfolioSummaryResponse.AssetDetailDto;
import com.github.raonjena99.multi_currency_ledger_service.portfolio.domain.CurrentPortfolio;
import com.github.raonjena99.multi_currency_ledger_service.portfolio.infrastructure.PortfolioQueryRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true) // 읽기 전용 트랜잭션으로 플러시(Flush) 부하 원천 차단
public class PortfolioQueryService {

    private final PortfolioQueryRepository portfolioQueryRepository;

    public PortfolioSummaryResponse getPortfolioSummary(UUID accountId) {
        // 뷰에서 사용자의 모든 자산 데이터(스냅샷)를 가져옴
        List<CurrentPortfolio> portfolios = portfolioQueryRepository.findAllByAccountId(accountId);

        // 개별 자산 DTO 매핑
        List<AssetDetailDto> assetDetails = portfolios.stream()
            .map(p -> new AssetDetailDto(
                p.getAssetCode(),
                p.getTotalQuantity(),
                p.getAvgUnitPrice(),
                p.getCurrentMarketPrice(),
                // 총 평가액 = 수량 * 현재 시장가
                p.getCurrentMarketPrice().compareTo(BigDecimal.ZERO) > 0 
                    ? p.getTotalQuantity().multiply(p.getCurrentMarketPrice())
                    : p.getTotalQuantity().multiply(p.getAvgUnitPrice()),
                p.getUnrealizedPnl()
            ))
            .collect(Collectors.toList());

        // 전체 포트폴리오 총합 계산
        BigDecimal totalAssetValue = assetDetails.stream()
            .map(AssetDetailDto::totalValue)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalUnrealizedPnl = assetDetails.stream()
            .map(AssetDetailDto::unrealizedPnl)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        // 최종 통합 DTO 반환
        return new PortfolioSummaryResponse(accountId, totalAssetValue, totalUnrealizedPnl, assetDetails);
    }
}
