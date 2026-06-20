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

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PortfolioQueryService {

    private final PortfolioQueryRepository queryRepository;
    private final ExchangeRateProvider exchangeRateProvider;

    public PortfolioSummaryResponse getPortfolioSummary(UUID accountId) {
        List<CurrentPortfolio> portfolios = queryRepository.findAllByAccountId(accountId);

        BigDecimal totalAssetValue = BigDecimal.ZERO;
        BigDecimal totalUnrealizedPnl = BigDecimal.ZERO;
        boolean finalStaleFlag = false;
        
        List<AssetDetailDto> dtos = new ArrayList<>(portfolios.size());

        for (CurrentPortfolio p : portfolios) {
            var rateInfo = exchangeRateProvider.getExchangeRate(p.getAssetCode(), "KRW");
            BigDecimal currentMarketPrice = rateInfo.rate();

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