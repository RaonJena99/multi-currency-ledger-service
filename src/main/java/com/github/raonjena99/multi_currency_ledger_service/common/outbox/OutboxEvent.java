package com.github.raonjena99.multi_currency_ledger_service.common.outbox;

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

    /**
     * OutboxEvent 객체를 생성합니다.
     *
     * @param aggregateType 이벤트를 발생시킨 애그리거트(Aggregate) 타입
     * @param aggregateId   애그리거트의 고유 식별자
     * @param eventType     이벤트 유형(토픽 이름 등)
     * @param payload       전송할 JSON 또는 직렬화된 이벤트 데이터
     */
    public OutboxEvent(String aggregateType, String aggregateId, String eventType, String payload) {
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.payload = payload;
    }

    /**
     * 메시지가 성공적으로 메시지 브로커로 전송되었음을 기록(processed = true)합니다.
     */
    public void markAsProcessed() {
        this.processed = true;
    }

    /**
     * 전송 실패를 기록하고, 재시도 횟수를 증가시킵니다.
     * 최대 재시도 횟수에 도달하면 Dead Letter(데드 레터) 큐로 처리하여 릴레이 워커의 무한 재시도를 방지합니다.
     *
     * @param error 실패의 원인이 된 에러 메시지
     */
    public void recordFailure(String error) {
        this.retryCount++;
        // DB 컬럼 길이 제한에 맞춰 에러 메시지를 자름
        this.errorMessage = error != null && error.length() > 500 ? error.substring(0, 500) : error;
        
        // 재시도 횟수가 3번 이상일 경우 수동 처리를 위해 Dead Letter 상태로 전환
        if (this.retryCount >= 3) {
            this.deadLetter = true;
            this.processed = true; 
        }
    }
}
