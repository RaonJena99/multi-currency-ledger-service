package com.github.raonjena99.multi_currency_ledger_service.common.outbox;

import java.util.concurrent.atomic.AtomicLong;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;

/**
 * 데드레터로 격리된 아웃박스 이벤트 수를 Gauge 로 노출합니다.
 *
 * <p>데드레터는 릴레이가 영구히 건너뛰므로, 0 이 아니면 잔고는 바뀌었는데 원장 기록 이벤트가 발행되지 못한
 * 거래가 남아 있다는 뜻입니다. 관리자 API 로 재발행하면 줄어듭니다.
 *
 * <p>프로메테우스 수집 때마다 DB 를 조회하지 않도록, 주기적으로 갱신한 값을 메모리에 두고 반환합니다.
 */
@Slf4j
@Component
public class OutboxDeadLetterMetrics {

    private final OutboxRepository outboxRepository;
    private final AtomicLong deadLetterCount = new AtomicLong();

    public OutboxDeadLetterMetrics(OutboxRepository outboxRepository, MeterRegistry meterRegistry) {
        this.outboxRepository = outboxRepository;
        Gauge.builder("outbox.dead_letters", deadLetterCount, AtomicLong::get)
                .description("데드레터로 격리된 아웃박스 이벤트 수. 0 이 아니면 원장 기록 이벤트가 발행되지 못한 거래가 있습니다.")
                .register(meterRegistry);
    }

    /**
     * 데드레터 건수를 다시 읽어 Gauge 값을 갱신합니다.
     */
    @Scheduled(fixedDelay = 60000)
    public void refresh() {
        try {
            deadLetterCount.set(outboxRepository.countByDeadLetterTrue());
        } catch (Exception e) {
            // 조회 실패 시 마지막 값을 유지한다. 0 으로 덮으면 장애 중에 알림이 꺼진다.
            log.error("Failed to refresh outbox dead letter metric", e);
        }
    }
}
