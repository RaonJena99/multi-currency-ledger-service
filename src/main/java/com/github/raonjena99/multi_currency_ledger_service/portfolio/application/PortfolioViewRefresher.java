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

/**
 * 거래(Trade) 발생 시 실시간으로 포트폴리오 데이터를 Redis에 갱신하는 컴포넌트입니다.
 * Write-Through 캐싱 전략을 적용하여, 트랜잭션이 성공적으로 커밋된 직후 최신 잔고 상태를 캐시에 반영합니다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PortfolioViewRefresher {

    private final RedisTemplate<String, Object> redisTemplate;
    private final AccountApi accountApi;

    /**
     * Account 도메인에서 거래가 완료된 후 발행하는 이벤트를 구독하여 Redis 캐시를 비동기(Async)로 업데이트합니다.
     * 트랜잭션이 성공적으로 커밋된 이후(AFTER_COMMIT)에만 동작하여 데이터 정합성을 보장합니다.
     * 캐시 업데이트 실패 시 기존 캐시를 삭제하여 다음 조회 시 DB 구체화 뷰에서(Fallback) 데이터를 읽어오도록 유도합니다.
     *
     * @param event 거래가 완료된 계좌와 거래 ID 정보를 담고 있는 이벤트 객체
     */
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
