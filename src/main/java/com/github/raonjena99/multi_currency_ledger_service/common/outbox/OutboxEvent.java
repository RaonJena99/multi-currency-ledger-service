package com.github.raonjena99.multi_currency_ledger_service.common.outbox;

import java.time.OffsetDateTime;

import com.github.raonjena99.multi_currency_ledger_service.common.domain.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 트랜잭셔널 아웃박스 패턴(Transactional Outbox Pattern)을 구현하기 위한 OutboxEvent(아웃박스 이벤트) 엔티티 클래스입니다.
 * 비즈니스 로직과 동일한 트랜잭션 내에 저장되어 메시지의 최소 1회(At-Least-Once) 전송을 보장합니다.
 */
@Entity
@Getter
@Table(name = "outbox_events", indexes = {
    @Index(name = "idx_outbox_event_processed", columnList = "processed, created_at"),
})
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OutboxEvent extends BaseEntity{

    @Id 
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "ob_event_seq")
    @SequenceGenerator(name = "ob_event_seq", sequenceName = "outbox_event_seq", allocationSize = 50)
    private Long id;

    @Column(name = "aggregate_type",nullable = false, length = 255)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false, length = 255)
    private String aggregateId;

    @Column(name = "event_type", nullable = false, length = 255)
    private String eventType;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String payload;

    @Column(nullable = false)
    private boolean processed = false;

    @Column(name = "retry_count", nullable = false)
    private int retryCount = 0;

    @Column(name = "error_message", length = 500)
    private String errorMessage;

    @Column(name = "dead_letter", nullable = false)
    private boolean deadLetter = false;

    @Column(name = "correlation_id", length = 100)
    private String correlationId;

    @Column(name = "locked_at")
    private OffsetDateTime lockedAt;

    /**
     * 다음 재시도가 허용되는 시각. 실패 시 지수 백오프로 미뤄집니다.
     *
     * <p>백오프 없이 폴링 주기(5초)마다 즉시 재시도하면, 브로커가 몇 분만 다운되어도
     * 재시도 예산이 소진되어 그 사이의 모든 이벤트가 데드레터로 빠집니다.
     */
    @Column(name = "next_attempt_at")
    private OffsetDateTime nextAttemptAt;

    /**
     * OutboxEvent 객체를 생성합니다.
     *
     * @param aggregateType 이벤트를 발생시킨 애그리거트(Aggregate) 타입
     * @param aggregateId   애그리거트의 고유 식별자
     * @param eventType     이벤트 유형(토픽 이름 등)
     * @param payload       전송할 JSON 또는 직렬화된 이벤트 데이터
     * @param correlationId 상호 연관성 있는 이벤트 식별자
     */
    public OutboxEvent(String aggregateType, String aggregateId, String eventType, String payload, String correlationId) {
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.payload = payload;
        this.correlationId = correlationId;
    }

    /**
     * 메시지가 성공적으로 메시지 브로커로 전송되었음을 기록(processed = true)합니다.
     */
    public void markAsProcessed() {
        this.processed = true;
    }

    /** 데드레터 전환 전 허용되는 최대 재시도 횟수. */
    static final int MAX_RETRY_COUNT = 10;

    /** 첫 실패 후 재시도까지의 대기 시간(초). 이후 실패마다 2배씩 늘어난다. */
    static final long BASE_BACKOFF_SECONDS = 30;

    /** 백오프 대기 시간의 상한(초). */
    static final long MAX_BACKOFF_SECONDS = 600;

    /**
     * 전송 실패를 기록하고, 재시도 횟수를 증가시킨 뒤 다음 시도 시각을 지수 백오프로 미룹니다.
     * 최대 재시도 횟수에 도달하면 Dead Letter(데드 레터) 큐로 처리하여 릴레이 워커의 무한 재시도를 방지합니다.
     *
     * <p>백오프(30초 → 60초 → … → 최대 10분)와 넉넉한 재시도 예산이 결합되어, 브로커가
     * 수십 분 다운되어도 이벤트가 데드레터로 빠지지 않고 복구 후 자동 재발행됩니다.
     *
     * @param error 실패의 원인이 된 에러 메시지
     */
    public void recordFailure(String error) {
        this.retryCount++;
        // DB 컬럼 길이 제한에 맞춰 에러 메시지를 자름
        this.errorMessage = error != null && error.length() > 500 ? error.substring(0, 500) : error;

        if (this.retryCount >= MAX_RETRY_COUNT) {
            this.deadLetter = true;
            this.processed = true;
            return;
        }

        long backoffSeconds = Math.min(
                BASE_BACKOFF_SECONDS * (1L << Math.min(this.retryCount - 1, 30)),
                MAX_BACKOFF_SECONDS);
        this.nextAttemptAt = OffsetDateTime.now().plusSeconds(backoffSeconds);
    }

    /**
     * 데드레터 상태의 이벤트를 다시 발행 대상으로 되돌립니다.
     *
     * <p>데드레터는 폴링 대상에서 영구 제외되므로, 이 경로가 없으면 브로커 장애 등으로
     * 격리된 이벤트를 자동으로 되살릴 방법이 없어 at-least-once 보장이 깨집니다.
     *
     * @throws IllegalStateException 데드레터 상태가 아닌 이벤트를 재적재하려는 경우
     */
    public void requeue() {
        if (!this.deadLetter) {
            throw new IllegalStateException("Only dead-lettered events can be requeued. id=" + this.id);
        }
        this.deadLetter = false;
        this.processed = false;
        this.retryCount = 0;
        this.nextAttemptAt = null;
        this.lockedAt = null;
    }

    // 잠금 처리
    public void lock(){
        this.lockedAt = OffsetDateTime.now();
    }

    // 잠금 해제
    public void unlock(){
        this.lockedAt = null;
    }
}
