package com.github.raonjena99.multi_currency_ledger_service.account.application;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;

import com.github.raonjena99.multi_currency_ledger_service.account.infrastructure.MonthlyAccountLedgerRepository;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Account(계좌) 도메인 관련 비즈니스 커스텀 지표(Metrics)를 프로메테우스에 노출하기 위한 설정 클래스입니다.
 * DB 부하 방지를 위해 실시간 조회가 아닌 스케줄러 기반의 메모리 캐시 방식을 사용합니다.
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class AccountMetricsConfiguration {

    private final MeterRegistry meterRegistry;
    private final MonthlyAccountLedgerRepository ledgerRepository;
    
    // 프로메테우스 수집(Scrape) 시 DB를 직접 찌르지 않도록 캐시로 사용되는 변수
    private final Map<String, AtomicReference<Double>> fiatBalanceCache = new ConcurrentHashMap<>();

    @PostConstruct
    public void initializeMetrics() {
        List<String> fiatCodes = ledgerRepository.findDistinctFiatCodes();
        if (fiatCodes.isEmpty()) {
            fiatCodes = List.of("KRW", "USD"); // 기본 모니터링 대상
        }

        for (String fiatCode : fiatCodes) {
            // 초기값을 0.0으로 세팅하여 캐시 생성
            fiatBalanceCache.put(fiatCode, new AtomicReference<>(0.0));
            
            // Gauge는 더 이상 DB를 조회하지 않고 메모리에 캐시된 AtomicReference의 값을 반환합니다.
            Gauge.builder("platform.total.fiat.balance", fiatBalanceCache.get(fiatCode), AtomicReference::get)
                    .description("플랫폼 내의 총 법정 화폐 보유 잔액")
                    .tag("currency", fiatCode)
                    .register(meterRegistry);
        }
        
        // 애플리케이션 기동 시 즉시 최초 1회 캐시 초기화
        refreshFiatBalances();
    }

    /**
     * 5분(300,000ms)마다 백그라운드에서 비동기적으로 캐시를 갱신합니다.
     * 이를 통해 프로메테우스 스크랩 주기(예: 15초)와 DB 부하를 완벽히 격리(Decoupling)합니다.
     */
    @Scheduled(fixedDelay = 300000)
    public void refreshFiatBalances() {
        fiatBalanceCache.keySet().forEach(fiatCode -> {
            try {
                BigDecimal totalBalance = ledgerRepository.sumLatestBalanceByAssetCode(fiatCode);
                double newValue = totalBalance != null ? totalBalance.doubleValue() : 0.0;
                fiatBalanceCache.get(fiatCode).set(newValue);
                log.debug("Fiat balance metric cached: {} = {}", fiatCode, newValue);
            } catch (Exception e) {
                log.error("Failed to refresh fiat balance metric for {}", fiatCode, e);
            }
        });
    }
}
