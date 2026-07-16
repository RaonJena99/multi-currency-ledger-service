package com.github.raonjena99.multi_currency_ledger_service.account.application;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.context.annotation.Configuration;

import com.github.raonjena99.multi_currency_ledger_service.account.infrastructure.MonthlyAccountLedgerRepository;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;

/**
 * Account(계좌) 도메인 관련 비즈니스 커스텀 지표(Metrics)를 프로메테우스에 노출하기 위한 설정 클래스입니다.
 */
@Configuration
@RequiredArgsConstructor
public class AccountMetricsConfiguration {

    private final MeterRegistry meterRegistry;
    private final MonthlyAccountLedgerRepository ledgerRepository;

    @PostConstruct
    public void initializeMetrics() {
        List<String> fiatCodes = ledgerRepository.findDistinctFiatCodes();
        if (fiatCodes.isEmpty()) {
            fiatCodes = List.of("KRW", "USD"); // 기본 모니터링 대상
        }

        for (String fiatCode : fiatCodes) {
            Gauge.builder("platform.total.fiat.balance", ledgerRepository, 
                    repo -> calculateTotalFiatBalance(repo, fiatCode))
                    .description("플랫폼 내의 총 법정 화폐 보유 잔액")
                    .tag("currency", fiatCode)
                    .register(meterRegistry);
        }
    }

    private double calculateTotalFiatBalance(MonthlyAccountLedgerRepository repo, String fiatCode) {
        try {
            BigDecimal totalBalance = repo.sumLatestBalanceByAssetCode(fiatCode);
            return totalBalance != null ? totalBalance.doubleValue() : 0.0;
        } catch (Exception e) {
            return 0.0;
        }
    }
}
