package com.github.raonjena99.multi_currency_ledger_service.portfolio.application;

import java.util.UUID;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.github.raonjena99.multi_currency_ledger_service.account.AccountApi;
import com.github.raonjena99.multi_currency_ledger_service.account.domain.event.BalanceAdjustedEvent;
import com.github.raonjena99.multi_currency_ledger_service.account.domain.event.TradeExecutedEvent;
import com.github.raonjena99.multi_currency_ledger_service.portfolio.application.dto.PortfolioCacheDto;
import com.github.raonjena99.multi_currency_ledger_service.portfolio.application.port.PortfolioCachePort;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 거래(Trade) 발생 시 실시간으로 포트폴리오 데이터를 Redis에 갱신하는 컴포넌트입니다.
 * Write-Through 캐싱 전략을 적용하여, 트랜잭션이 성공적으로 커밋된 직후 최신 잔고 상태를 캐시에 반영합니다.
 *
 * <p><b>실패 시 불변식</b>: 잔고 변경이 커밋된 뒤 이 컴포넌트가 캐시를 <b>갱신하지 못했다면 반드시
 * 삭제해야</b> 합니다. 어떤 실패 경로(락 획득 실패, 인터럽트, Redis 오류)든 기존 캐시를 그대로 두면
 * 커밋된 거래 이전의 잔고가 TTL(1시간) 동안 계속 서빙됩니다. 삭제된 캐시는 다음 조회가 DB 에서
 * 재구성하며, Eviction Storm 은 PortfolioQueryService 의 Double-Checked Spin Lock 이 방어합니다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PortfolioViewRefresher {

    private final PortfolioCachePort portfolioCachePort;
    private final AccountApi accountApi;

    /**
     * Account 도메인에서 거래가 완료된 후 발행하는 이벤트를 구독하여 Redis 캐시를 비동기(Async)로 업데이트합니다.
     * 트랜잭션이 성공적으로 커밋된 이후(AFTER_COMMIT)에만 동작하여 데이터 정합성을 보장합니다.
     *
     * @param event 거래가 완료된 계좌와 거래 ID 정보를 담고 있는 이벤트 객체
     */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void updateRedisCache(TradeExecutedEvent event) {
        log.debug("Trade committed (TradeID: {}). Updating Redis Cache for account: {}", event.tradeId(), event.accountId());
        refresh(event.accountId(), "TradeID: " + event.tradeId());
    }

    /**
     * 거래 외 경로(대사 수수료 보정 등)로 잔고가 변경된 경우에도 캐시를 갱신합니다.
     * 이 구독이 없으면 보정으로 바뀐 잔고가 캐시 TTL 동안 포트폴리오 조회에 반영되지 않습니다.
     *
     * @param event 잔고 조정 이벤트
     */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onBalanceAdjusted(BalanceAdjustedEvent event) {
        log.debug("Balance adjusted. Updating Redis Cache for account: {}", event.accountId());
        refresh(event.accountId(), "BalanceAdjustment");
    }

    private void refresh(UUID accountId, String contextId) {
        String lockKey = "lock:portfolio:" + accountId;
        boolean locked = false;
        try {
            // 비동기 스레드간 순서 역전 방지를 위한 분산 락
            long endTime = System.currentTimeMillis() + 3000;
            while (System.currentTimeMillis() < endTime) {
                locked = portfolioCachePort.tryAcquireLock(lockKey, 5);
                if (locked) break;
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }

            if (!locked) {
                // 갱신을 포기하는 모든 경로에서 기존 캐시를 삭제해야 한다. 그대로 두면
                // 커밋된 거래 이전의 잔고가 TTL 동안 계속 서빙된다(치명적 Stale Data).
                log.warn("Failed to acquire lock for updating cache ({}). Evicting stale cache instead.", contextId);
                evictQuietly(accountId);
                return;
            }

            var currentBalances = accountApi.getBalances(accountId);
            String baseCurrency = accountApi.getBaseCurrency(accountId);

            var assetBalances = currentBalances.stream()
                .map(b -> new PortfolioCacheDto.AssetBalance(b.assetCode(), b.totalQuantity(), b.avgUnitPrice(), b.quoteCurrency()))
                .toList();

            var cacheDto = new PortfolioCacheDto(accountId, baseCurrency, assetBalances);

            portfolioCachePort.savePortfolioCache(accountId, cacheDto);

            log.info("Successfully refreshed Redis portfolio cache for account: {}", accountId);
        } catch (Exception e) {
            // DB나 API 연동 실패 시 기존 캐시를 유지하면 사용자가 1시간 동안 과거의 낡은 잔고(Stale Data)를 보게 된다.
            // Eviction Storm은 이미 PortfolioQueryService의 Double-Checked Spin Lock으로 방어되어 있으므로 안심하고 캐시를 날린다.
            log.error("Failed to update Redis cache ({}). Evicting cache to force real-time refresh on next query.", contextId, e);
            evictQuietly(accountId);
        } finally {
            if (locked) {
                releaseQuietly(lockKey);
            }
        }
    }

    private void evictQuietly(UUID accountId) {
        try {
            portfolioCachePort.evictPortfolioCache(accountId);
        } catch (Exception e) {
            // Redis 자체가 죽어 삭제도 실패하면 조회 경로의 캐시 읽기도 함께 실패하므로
            // Stale Data 가 서빙되지는 않는다. 복구 후 잔존 캐시는 TTL 로만 정리되므로 기록해 둔다.
            log.error("Failed to evict portfolio cache for account: {}", accountId, e);
        }
    }

    private void releaseQuietly(String lockKey) {
        try {
            portfolioCachePort.releaseLock(lockKey);
        } catch (Exception e) {
            log.warn("Failed to release portfolio cache lock: {}", lockKey, e);
        }
    }
}
