package com.github.raonjena99.multi_currency_ledger_service.common.outbox;

import java.sql.Timestamp;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;

/**
 * 발행을 기다리는 아웃박스 이벤트의 건수와, 그중 가장 오래된 이벤트의 나이를 Gauge 로 노출합니다.
 *
 * <p>릴레이가 거래 속도를 따라가지 못하면 잔고는 즉시 바뀌는데 분개는 그만큼 늦게 기록됩니다. 그동안 거래
 * 내역 조회는 404 이고, 정합성 점검은 발행 대기 중인 계좌를 건너뜁니다. 건수만으로는 "잠깐 몰렸다"와
 * "계속 밀린다"를 구분하기 어려우므로 가장 오래된 대기 이벤트의 나이를 함께 냅니다. 경보는 나이로 겁니다.
 *
 * <p>데드레터는 릴레이가 건너뛰는 행이라 여기서 세지 않습니다({@link OutboxDeadLetterMetrics}).
 *
 * <p>프로메테우스 수집 때마다 DB 를 조회하지 않도록 주기적으로 읽어 둡니다. 나이는 읽어 둔 생성 시각으로
 * 수집 시점에 계산하므로 갱신 사이에도 늘어납니다.
 */
@Slf4j
@Component
public class OutboxBacklogMetrics {

    private static final String PENDING_SUMMARY = """
            SELECT count(*) AS pending, min(created_at) AS oldest
            FROM outbox_events
            WHERE processed = false AND dead_letter = false
            """;

    /** 대기 이벤트가 없을 때의 표식. */
    private static final long NONE = -1L;

    private final JdbcTemplate jdbcTemplate;
    private final AtomicLong pendingCount = new AtomicLong();
    private final AtomicLong oldestCreatedAtMillis = new AtomicLong(NONE);

    public OutboxBacklogMetrics(JdbcTemplate jdbcTemplate, MeterRegistry meterRegistry) {
        this.jdbcTemplate = jdbcTemplate;
        Gauge.builder("outbox.backlog", pendingCount, AtomicLong::get)
                .description("발행을 기다리는 아웃박스 이벤트 수(데드레터 제외)")
                .register(meterRegistry);
        Gauge.builder("outbox.oldest_pending_age", oldestCreatedAtMillis, OutboxBacklogMetrics::ageSeconds)
                .description("발행을 기다리는 가장 오래된 아웃박스 이벤트의 나이. 계속 늘면 릴레이가 거래 속도를 따라가지 못하는 것입니다.")
                .baseUnit("seconds")
                .register(meterRegistry);
    }

    private static double ageSeconds(AtomicLong oldestCreatedAtMillis) {
        long oldest = oldestCreatedAtMillis.get();
        if (oldest == NONE) {
            return 0;
        }
        return Math.max(0, System.currentTimeMillis() - oldest) / 1000.0;
    }

    /**
     * 대기 건수와 가장 오래된 대기 이벤트의 생성 시각을 다시 읽습니다.
     */
    @Scheduled(fixedDelay = 15000)
    public void refresh() {
        try {
            jdbcTemplate.query(PENDING_SUMMARY, rs -> {
                pendingCount.set(rs.getLong("pending"));
                Timestamp oldest = rs.getTimestamp("oldest");
                oldestCreatedAtMillis.set(oldest == null ? NONE : oldest.getTime());
            });
        } catch (Exception e) {
            // 조회 실패 시 마지막 값을 유지한다. 0 으로 덮으면 장애 중에 경보가 꺼진다.
            log.error("Failed to refresh outbox backlog metrics", e);
        }
    }
}
