package com.github.raonjena99.multi_currency_ledger_service.portfolio.application;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.github.raonjena99.multi_currency_ledger_service.common.port.ExchangeRateProvider;
import com.github.raonjena99.multi_currency_ledger_service.portfolio.application.dto.PortfolioSummaryResponse;
import com.github.raonjena99.multi_currency_ledger_service.portfolio.application.dto.PortfolioSummaryResponse.AssetDetailDto;
import com.github.raonjena99.multi_currency_ledger_service.portfolio.domain.CurrentPortfolio;
import com.github.raonjena99.multi_currency_ledger_service.portfolio.infrastructure.PortfolioQueryRepository;
import com.github.raonjena99.multi_currency_ledger_service.account.AccountApi;

import lombok.RequiredArgsConstructor;

/**
 * 포트폴리오 조회를 담당하는 PortfolioQueryService(포트폴리오 조회 서비스) 클래스입니다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PortfolioQueryService {

    private final PortfolioQueryRepository portfolioQueryRepository;
    private final ExchangeRateProvider exchangeRateProvider;
    private final AccountApi accountApi;

    /**
     * 특정 계좌의 포트폴리오 요약 정보를 조회합니다.
     * @param accountId 조회할 계좌 ID
     * @return 조회된 PortfolioSummaryResponse(포트폴리오 요약 응답) 객체
     */
    public PortfolioSummaryResponse getPortfolioSummary(UUID accountId) {
        String baseCurrency = accountApi.getBaseCurrency(accountId);

        List<CurrentPortfolio> portfolios = portfolioQueryRepository.findAllByAccountId(accountId);
        if (portfolios.isEmpty()) {
            return new PortfolioSummaryResponse(accountId, BigDecimal.ZERO, BigDecimal.ZERO, false, List.of());
        }

        BigDecimal totalAssetValue = BigDecimal.ZERO;
        BigDecimal totalUnrealizedPnl = BigDecimal.ZERO;
        boolean finalStaleFlag = false;
        
        List<AssetDetailDto> dtos = new ArrayList<>(portfolios.size());

        List<String> targetAssets = portfolios.stream().map(CurrentPortfolio::getAssetCode).toList();
        java.util.Map<String, ExchangeRateProvider.ExchangeRate> exchangeRates = exchangeRateProvider.getExchangeRates(targetAssets, baseCurrency);

        for (CurrentPortfolio p : portfolios) {
            // 미리 조회한 시장 환율 정보를 사용하여 자산의 현재 가치를 계산합니다.
            var rateInfo = exchangeRates.get(p.getAssetCode());
            BigDecimal currentMarketPrice = rateInfo.rate();

            // 총 가치(Total Value) 및 미실현 손익(Unrealized PnL) 계산
            BigDecimal totalValue = currentMarketPrice.multiply(p.getTotalQuantity());
            BigDecimal unrealizedPnl = currentMarketPrice.subtract(p.getAvgUnitPrice()).multiply(p.getTotalQuantity());

            dtos.add(new AssetDetailDto(
                    p.getAssetCode(), p.getTotalQuantity(), p.getAvgUnitPrice(),
                    currentMarketPrice, totalValue, unrealizedPnl, rateInfo.isStale()
            ));

            totalAssetValue = totalAssetValue.add(totalValue);
            totalUnrealizedPnl = totalUnrealizedPnl.add(unrealizedPnl);
            
            if (rateInfo.isStale()) finalStaleFlag = true;
        }

        return new PortfolioSummaryResponse(
                accountId, totalAssetValue, totalUnrealizedPnl, finalStaleFlag, dtos
        );
    }
}