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

/**
 * 포트폴리오 조회를 담당하는 PortfolioQueryService(포트폴리오 조회 서비스) 클래스입니다.
 * CQRS 패턴의 Query(읽기) 모델을 담당하며, 실시간 조회를 위해 Redis 캐시(Write-Through)를 우선 활용합니다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PortfolioQueryService {

    private final PortfolioQueryRepository portfolioQueryRepository;
    private final ExchangeRateProvider exchangeRateProvider;
    private final AccountApi accountApi;
    private final RedisTemplate<String, Object> redisTemplate;

    /**
     * 특정 계좌의 실시간 포트폴리오 요약 정보를 조회합니다.
     * 1차적으로 Redis 캐시에서 잔고를 조회하고(Cache Hit), 없을 경우 DB 구체화 뷰에서 조회합니다(Fallback).
     * 외부 환율 API를 통해 실시간 시장 가격을 반영하여 총 자산 가치 및 미실현 손익(Unrealized PnL)을 계산합니다.
     *
     * @param accountId 포트폴리오를 조회할 대상 계좌 ID
     * @return 계산된 총 자산 가치, 미실현 손익, 각 자산별 상세 정보가 포함된 요약 응답 객체
     */
    public PortfolioSummaryResponse getPortfolioSummary(UUID accountId) {
        String baseCurrency = accountApi.getBaseCurrency(accountId);
        String redisKey = "portfolio:account:" + accountId;

        PortfolioCacheDto cachedDto = (PortfolioCacheDto) redisTemplate.opsForValue().get(redisKey);
        
        List<AssetDetailDto> dtos = new ArrayList<>();
        BigDecimal totalAssetValue = BigDecimal.ZERO;
        BigDecimal totalUnrealizedPnl = BigDecimal.ZERO;
        boolean finalStaleFlag = false;

        if (cachedDto != null && cachedDto.getBalances() != null) {
            // 캐시 HIT: 캐시 데이터를 기반으로 실시간 환율 적용
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
            // 캐시 MISS: DB 구체화 뷰(Materialized View)에서 Fallback 조회
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