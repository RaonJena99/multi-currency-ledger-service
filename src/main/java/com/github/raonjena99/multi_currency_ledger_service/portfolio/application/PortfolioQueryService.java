package com.github.raonjena99.multi_currency_ledger_service.portfolio.application;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.github.raonjena99.multi_currency_ledger_service.account.AccountApi;
import com.github.raonjena99.multi_currency_ledger_service.common.port.ExchangeRateProvider;
import com.github.raonjena99.multi_currency_ledger_service.portfolio.application.dto.PortfolioCacheDto;
import com.github.raonjena99.multi_currency_ledger_service.portfolio.application.dto.PortfolioSummaryResponse;
import com.github.raonjena99.multi_currency_ledger_service.portfolio.application.dto.PortfolioSummaryResponse.AssetDetailDto;
import com.github.raonjena99.multi_currency_ledger_service.portfolio.application.port.PortfolioCachePort;
import com.github.raonjena99.multi_currency_ledger_service.portfolio.domain.PortfolioValuation;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 포트폴리오 조회를 담당하는 PortfolioQueryService(포트폴리오 조회 서비스) 클래스입니다.
 * CQRS 패턴의 Query(읽기) 모델을 담당하며, 실시간 조회를 위해 Redis 캐시를 우선 활용합니다.
 *
 * <p><b>트랜잭션을 열지 않습니다.</b> 이전 구현은 {@code @Transactional(readOnly = true)} 안에서
 * Redis 분산 락을 최대 3초 동안 {@code Thread.sleep} 으로 대기했습니다. 그 시간 내내 Hikari
 * 커넥션(풀 크기 20)을 붙잡고 잠들기 때문에, 캐시 미스가 동시에 몰리면 커넥션 풀이 고갈되어
 * 스스로 서비스를 마비시킵니다. 실제 DB 접근은 {@link AccountApi} 구현체가 자기 트랜잭션에서 수행합니다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PortfolioQueryService {

    private static final long LOCK_WAIT_MILLIS = 3000L;
    private static final long LOCK_POLL_MILLIS = 50L;
    private static final long LOCK_TTL_SECONDS = 10L;

    private final ExchangeRateProvider exchangeRateProvider;
    private final AccountApi accountApi;
    private final PortfolioCachePort portfolioCachePort;

    /**
     * 특정 계좌의 실시간 포트폴리오 요약 정보를 조회합니다.
     *
     * @param accountId 포트폴리오를 조회할 대상 계좌 ID
     * @return 계산된 총 자산 가치, 미실현 손익, 각 자산별 상세 정보가 포함된 요약 응답 객체
     */
    public PortfolioSummaryResponse getPortfolioSummary(UUID accountId) {
        String baseCurrency = accountApi.getBaseCurrency(accountId);

        PortfolioCacheDto snapshot = loadSnapshot(accountId, baseCurrency);

        List<AssetDetailDto> dtos = new ArrayList<>();
        BigDecimal totalAssetValue = BigDecimal.ZERO;
        BigDecimal totalUnrealizedPnl = BigDecimal.ZERO;
        boolean finalStaleFlag = false;

        // 자산 코드와 견적 통화를 한 번에 모아 Batch 로 환율을 조회한다 (N+1 방지).
        // 중복 조회를 피하기 위해 순서를 유지하는 집합을 사용한다.
        LinkedHashSet<String> requiredCurrencies = new LinkedHashSet<>();
        snapshot.getBalances().forEach(b -> {
            requiredCurrencies.add(b.getAssetCode());
            requiredCurrencies.add(b.getQuoteCurrency());
        });

        Map<String, ExchangeRateProvider.ExchangeRate> exchangeRates =
                exchangeRateProvider.getExchangeRates(new ArrayList<>(requiredCurrencies), baseCurrency);

        for (PortfolioCacheDto.AssetBalance p : snapshot.getBalances()) {
            var rateInfo = exchangeRates.get(p.getAssetCode());
            if (rateInfo == null) continue;

            var quoteRateInfo = exchangeRates.get(p.getQuoteCurrency());
            if (quoteRateInfo == null) continue;

            PortfolioValuation valuation = PortfolioValuation.calculate(
                p.getTotalQuantity(), p.getAvgUnitPrice(), rateInfo.rate(), quoteRateInfo.rate()
            );

            boolean stale = rateInfo.isStale() || quoteRateInfo.isStale();

            dtos.add(new AssetDetailDto(
                p.getAssetCode(),
                p.getTotalQuantity(),
                p.getAvgUnitPrice(),
                valuation.currentMarketPrice(),
                valuation.totalValue(),
                valuation.unrealizedPnl(),
                stale
            ));

            totalAssetValue = totalAssetValue.add(valuation.totalValue());
            totalUnrealizedPnl = totalUnrealizedPnl.add(valuation.unrealizedPnl());
            if (stale) finalStaleFlag = true;
        }

        return new PortfolioSummaryResponse(
                accountId, totalAssetValue, totalUnrealizedPnl, finalStaleFlag, dtos
        );
    }

    /**
     * 캐시에서 잔고 스냅샷을 읽고, 없으면 DB 에서 재구성해 캐시에 채웁니다.
     *
     * <p>캐시 미스와 Redis 장애를 구분합니다. Redis 가 죽었을 때 예외를 그대로 올리면 조회 API 가
     * 함께 죽으므로, 락을 얻지 못하거나 캐시 접근이 실패한 경우에는 DB 에서 직접 읽어 응답합니다.
     */
    private PortfolioCacheDto loadSnapshot(UUID accountId, String baseCurrency) {
        Optional<PortfolioCacheDto> cached = readCacheQuietly(accountId);
        if (cached.isPresent() && cached.get().getBalances() != null) {
            return cached.get();
        }

        String lockKey = "lock:portfolio:" + accountId;
        boolean locked = tryAcquireLockQuietly(lockKey);

        if (!locked) {
            // 캐시 스탬피드 방어에 실패했더라도 조회 자체는 성공시킨다.
            log.warn("포트폴리오 캐시 갱신 락 획득 실패. DB 에서 직접 조회합니다. account={}", accountId);
            return readFromDatabase(accountId, baseCurrency);
        }

        try {
            // Double-Checked Locking
            Optional<PortfolioCacheDto> recheck = readCacheQuietly(accountId);
            if (recheck.isPresent() && recheck.get().getBalances() != null) {
                return recheck.get();
            }

            PortfolioCacheDto rebuilt = readFromDatabase(accountId, baseCurrency);
            try {
                // 깡통 계좌도 캐싱하여 Cache Penetration 을 방어한다.
                portfolioCachePort.savePortfolioCache(accountId, rebuilt);
            } catch (Exception e) {
                log.warn("포트폴리오 캐시 저장 실패. 조회는 계속 진행합니다: {}", e.getMessage());
            }
            return rebuilt;
        } finally {
            try {
                portfolioCachePort.releaseLock(lockKey);
            } catch (Exception e) {
                log.warn("포트폴리오 캐시 락 해제 실패: {}", e.getMessage());
            }
        }
    }

    private PortfolioCacheDto readFromDatabase(UUID accountId, String baseCurrency) {
        List<PortfolioCacheDto.AssetBalance> balances = accountApi.getBalances(accountId).stream()
                .map(b -> new PortfolioCacheDto.AssetBalance(
                        b.assetCode(), b.totalQuantity(), b.avgUnitPrice(), b.quoteCurrency()))
                .toList();
        return new PortfolioCacheDto(accountId, baseCurrency, new ArrayList<>(balances));
    }

    private Optional<PortfolioCacheDto> readCacheQuietly(UUID accountId) {
        try {
            return portfolioCachePort.getPortfolioCache(accountId);
        } catch (Exception e) {
            // Redis 장애를 캐시 미스로 강등한다. 그러지 않으면 조회 API 가 Redis 와 함께 죽는다.
            log.warn("포트폴리오 캐시 조회 실패. 캐시 미스로 처리합니다: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private boolean tryAcquireLockQuietly(String lockKey) {
        long deadline = System.currentTimeMillis() + LOCK_WAIT_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            try {
                if (portfolioCachePort.tryAcquireLock(lockKey, LOCK_TTL_SECONDS)) {
                    return true;
                }
            } catch (Exception e) {
                log.warn("포트폴리오 캐시 락 획득 실패(인프라 오류): {}", e.getMessage());
                return false;
            }
            try {
                Thread.sleep(LOCK_POLL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }
}
