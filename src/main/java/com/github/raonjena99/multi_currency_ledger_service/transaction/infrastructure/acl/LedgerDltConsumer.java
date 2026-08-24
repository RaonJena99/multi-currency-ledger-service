package com.github.raonjena99.multi_currency_ledger_service.transaction.infrastructure.acl;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.github.raonjena99.multi_currency_ledger_service.common.telemetry.CorrelationIdFilter;
import com.github.raonjena99.multi_currency_ledger_service.transaction.domain.LedgerDeadLetter;
import com.github.raonjena99.multi_currency_ledger_service.transaction.infrastructure.LedgerDeadLetterRepository;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;

/**
 * 처리에 완전히 실패한 원장 기록 메시지(Dead Letter)를 수집하는 컨슈머입니다.
 *
 * <p>이 지점에 메시지가 도달했다는 것은 <b>잔고는 변경되었는데 대응하는 분개가 없다</b>는 뜻입니다.
 * 로그만 남기면 아무도 알 수 없으므로 DB 에 격리 저장하고 지표를 올려 알림이 가능하게 합니다.
 */
@Slf4j
@Component
public class LedgerDltConsumer {

    private final LedgerDeadLetterRepository deadLetterRepository;
    private final Counter deadLetterCounter;

    public LedgerDltConsumer(LedgerDeadLetterRepository deadLetterRepository, MeterRegistry meterRegistry) {
        this.deadLetterRepository = deadLetterRepository;
        this.deadLetterCounter = Counter.builder("ledger.dead_letter.count")
                .description("복식부기 원장 기록에 완전히 실패한 메시지 수. 0 이 아니면 잔고와 원장이 불일치 상태입니다.")
                .register(meterRegistry);
    }

    /**
     * 원본 토픽에 ".DLT"가 붙은 토픽을 리스닝하여 실패 건을 격리합니다.
     *
     * <p>헤더는 optional 로 받습니다. 필수로 선언하면 헤더 없는 메시지가 들어올 때 변환이 실패해
     * DLT 컨슈머 자체가 무한 재시도에 빠집니다.
     *
     * @param payload       처리에 실패한 원본 페이로드
     * @param errorMessage  실패 원인
     * @param originalTopic 원본 토픽 이름
     * @param correlationId 분산 추적 식별자
     */
    @KafkaListener(topics = "LedgerRecordingCommand.DLT", groupId = "ledger-dlt-alert-group")
    @Transactional
    public void consumeDlt(
            String payload,
            @Header(name = KafkaHeaders.EXCEPTION_MESSAGE, required = false) String errorMessage,
            @Header(name = KafkaHeaders.ORIGINAL_TOPIC, required = false) String originalTopic,
            @Header(name = CorrelationIdFilter.MDC_KEY, required = false) String correlationId) {

        log.error("[CRITICAL] 원장 기록 완전 실패 (DLT). 잔고와 원장이 불일치 상태입니다. "
                + "originalTopic={}, cause={}, payload={}", originalTopic, errorMessage, payload);

        deadLetterRepository.save(LedgerDeadLetter.isolate(originalTopic, errorMessage, payload, correlationId));
        deadLetterCounter.increment();
    }
}
