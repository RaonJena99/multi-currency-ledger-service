package com.github.raonjena99.multi_currency_ledger_service.transaction.application;

import java.util.concurrent.atomic.AtomicLong;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.github.raonjena99.multi_currency_ledger_service.transaction.infrastructure.LedgerDeadLetterRepository;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;

/**
 * 미해결 원장 데드레터 건수를 Gauge 로 노출합니다.
 *
 * <p>{@code ledger.dead_letter.count} 는 격리될 때마다 오르기만 하는 Counter 라, 운영자가 모두 해결해도
 * 값이 줄지 않아 알림 조건으로 쓸 수 없습니다. 이 Gauge 는 "지금 잔고와 원장이 어긋난 건이 몇 개인가"를
 * 보여주므로 {@code > 0} 을 그대로 알림 조건으로 쓸 수 있습니다.
 *
 * <p>프로메테우스 수집 때마다 DB 를 조회하지 않도록, 주기적으로 갱신한 값을 메모리에 두고 반환합니다.
 */
@Slf4j
@Component
public class LedgerDeadLetterMetrics {

    private final LedgerDeadLetterRepository deadLetterRepository;
    private final AtomicLong unresolvedCount = new AtomicLong();

    public LedgerDeadLetterMetrics(LedgerDeadLetterRepository deadLetterRepository, MeterRegistry meterRegistry) {
        this.deadLetterRepository = deadLetterRepository;
        Gauge.builder("ledger.dead_letter.unresolved", unresolvedCount, AtomicLong::get)
                .description("해결되지 않은 원장 데드레터 수. 0 이 아니면 잔고와 원장이 어긋난 거래가 남아 있습니다.")
                .register(meterRegistry);
    }

    /**
     * 미해결 건수를 다시 읽어 Gauge 값을 갱신합니다.
     */
    @Scheduled(fixedDelay = 60000)
    public void refresh() {
        try {
            unresolvedCount.set(deadLetterRepository.countUnresolved());
        } catch (Exception e) {
            // 조회 실패 시 마지막 값을 유지한다. 0 으로 덮으면 장애 중에 알림이 꺼진다.
            log.error("Failed to refresh unresolved ledger dead letter metric", e);
        }
    }
}
