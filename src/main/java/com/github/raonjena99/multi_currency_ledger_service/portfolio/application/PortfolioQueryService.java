package com.github.raonjena99.multi_currency_ledger_service.portfolio.application;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.github.raonjena99.multi_currency_ledger_service.account.AccountApi;
import com.github.raonjena99.multi_currency_ledger_service.common.port.ExchangeRateProvider;
import com.github.raonjena99.multi_currency_ledger_service.portfolio.application.dto.PortfolioSummaryResponse;
import com.github.raonjena99.multi_currency_ledger_service.portfolio.application.dto.PortfolioSummaryResponse.AssetDetailDto;
import com.github.raonjena99.multi_currency_ledger_service.portfolio.application.dto.PortfolioCacheDto;
import com.github.raonjena99.multi_currency_ledger_service.portfolio.domain.CurrentPortfolio;
import com.github.raonjena99.multi_currency_ledger_service.portfolio.infrastructure.PortfolioQueryRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PortfolioQueryService {

    private final PortfolioQueryRepository portfolioQueryRepository;
    private final ExchangeRateProvider exchangeRateProvider;
    private final AccountApi accountApi;
    private final RedisTemplate<String, Object> redisTemplate;

    public PortfolioSummaryResponse getPortfolioSummary(UUID accountId) {
        String baseCurrency = accountApi.getBaseCurrency(accountId);
        String redisKey = "portfolio:account:" + accountId;

        PortfolioCacheDto cachedDto = (PortfolioCacheDto) redisTemplate.opsForValue().get(redisKey);
        
        List<AssetDetailDto> dtos = new ArrayList<>();
        BigDecimal totalAssetValue = BigDecimal.ZERO;
        BigDecimal totalUnrealizedPnl = BigDecimal.ZERO;
        boolean finalStaleFlag = false;

        if (cachedDto != null && cachedDto.getBalances() != null) {
            List<String> targetAssets = cachedDto.getBalances().stream().map(PortfolioCacheDto.AssetBalance::getAssetCode).toList();
            Map<String, ExchangeRateProvider.ExchangeRate> exchangeRates = exchangeRateProvider.getExchangeRates(targetAssets, baseCurrency);

            for (PortfolioCacheDto.AssetBalance p : cachedDto.getBalances()) {
                var rateInfo = exchangeRates.get(p.getAssetCode());
                if(rateInfo == null) continue;

                BigDecimal currentMarketPrice = rateInfo.rate();
                BigDecimal totalValue = currentMarketPrice.multiply(p.getTotalQuantity());

                var quoteRateInfo = exchangeRateProvider.getExchangeRate(p.getQuoteCurrency(), baseCurrency);
                BigDecimal convertedAvgUnitPrice = p.getAvgUnitPrice().multiply(quoteRateInfo.rate());
                BigDecimal unrealizedPnl = currentMarketPrice.subtract(convertedAvgUnitPrice).multiply(p.getTotalQuantity());

                dtos.add(new AssetDetailDto(p.getAssetCode(), p.getTotalQuantity(), p.getAvgUnitPrice(), currentMarketPrice, totalValue, unrealizedPnl, rateInfo.isStale()));
                totalAssetValue = totalAssetValue.add(totalValue);
                totalUnrealizedPnl = totalUnrealizedPnl.add(unrealizedPnl);
                if (rateInfo.isStale()) finalStaleFlag = true;
            }
        } else {
            List<CurrentPortfolio> portfolios = portfolioQueryRepository.findAllByAccountId(accountId);
            if (portfolios.isEmpty()) {
                return new PortfolioSummaryResponse(accountId, BigDecimal.ZERO, BigDecimal.ZERO, false, List.of());
            }

            List<String> targetAssets = portfolios.stream().map(CurrentPortfolio::getAssetCode).toList();
            Map<String, ExchangeRateProvider.ExchangeRate> exchangeRates = exchangeRateProvider.getExchangeRates(targetAssets, baseCurrency);

            for (CurrentPortfolio p : portfolios) {
                var rateInfo = exchangeRates.get(p.getAssetCode());
                if(rateInfo == null) continue;

                BigDecimal currentMarketPrice = rateInfo.rate();
                BigDecimal totalValue = currentMarketPrice.multiply(p.getTotalQuantity());
                
                var quoteRateInfo = exchangeRateProvider.getExchangeRate(p.getQuoteCurrency(), baseCurrency);
                BigDecimal convertedAvgUnitPrice = p.getAvgUnitPrice().multiply(quoteRateInfo.rate());
                BigDecimal unrealizedPnl = currentMarketPrice.subtract(convertedAvgUnitPrice).multiply(p.getTotalQuantity());

                dtos.add(new AssetDetailDto(p.getAssetCode(), p.getTotalQuantity(), p.getAvgUnitPrice(), currentMarketPrice, totalValue, unrealizedPnl, rateInfo.isStale()));
                totalAssetValue = totalAssetValue.add(totalValue);
                totalUnrealizedPnl = totalUnrealizedPnl.add(unrealizedPnl);
                if (rateInfo.isStale()) finalStaleFlag = true;
            }
        }
        
        return new PortfolioSummaryResponse(
                accountId, totalAssetValue, totalUnrealizedPnl, finalStaleFlag, dtos
        );
    }
}