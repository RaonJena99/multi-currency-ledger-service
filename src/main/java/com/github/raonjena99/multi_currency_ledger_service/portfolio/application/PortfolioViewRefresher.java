package com.github.raonjena99.multi_currency_ledger_service.portfolio.application;

import java.time.Duration;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.github.raonjena99.multi_currency_ledger_service.account.AccountApi;
import com.github.raonjena99.multi_currency_ledger_service.account.domain.event.TradeExecutedEvent;
import com.github.raonjena99.multi_currency_ledger_service.portfolio.application.dto.PortfolioCacheDto;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class PortfolioViewRefresher {

    private final RedisTemplate<String, Object> redisTemplate;
    private final AccountApi accountApi;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void updateRedisCache(TradeExecutedEvent event) {
        log.debug("Trade committed (TradeID: {}). Updating Redis Cache for account: {}", event.tradeId(), event.accountId());
        
        try {
            var currentBalances = accountApi.getBalances(event.accountId());
            String baseCurrency = accountApi.getBaseCurrency(event.accountId());
            
            var assetBalances = currentBalances.stream()
                .map(b -> new PortfolioCacheDto.AssetBalance(b.assetCode(), b.totalQuantity(), b.avgUnitPrice(), b.quoteCurrency()))
                .toList();

            var cacheDto = new PortfolioCacheDto(event.accountId(), baseCurrency, assetBalances);
            
            String redisKey = "portfolio:account:" + event.accountId();
            redisTemplate.opsForValue().set(redisKey, cacheDto, Duration.ofHours(1));
            
            log.info("Successfully refreshed Redis portfolio cache for account: {}", event.accountId());
        } catch (Exception e) {
            log.error("Failed to update Redis cache. Next read will fall back to DB.", e);
            redisTemplate.delete("portfolio:account:" + event.accountId());
        }
    }
}
