package com.github.raonjena99.multi_currency_ledger_service.common.outbox;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Transactional Outbox Pattern을 위한 엔티티
 * 비즈니스 로직(원장 기록)과 동일한 트랜잭션에서 이 테이블에 데이터가 적재됨
 */
@Entity
@Getter
@Table(name = "outbox_events")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OutboxEvent {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "aggregate_type",nullable = false, length = 255)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false, length = 255)
    private String aggregateId;

    @Column(name = "event_type", nullable = false, length = 255)
    private String eventType;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String payload;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private boolean processed = false;

    @Column(name = "retry_count", nullable = false)
    private int retryCount = 0;

    @Column(name = "error_message", length = 500)
    private String errorMessage;

    @Column(name = "dead_letter", nullable = false)
    private boolean deadLetter = false;


    public OutboxEvent(String aggregateType, String aggregateId, String eventType, String payload) {
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.payload = payload;
        this.createdAt = LocalDateTime.now();
    }

    public void markAsProcessed() {
        this.processed = true;
    }

    public void recordFailure(String error) {
        this.retryCount++;
        this.errorMessage = error != null && error.length() > 500 ? error.substring(0, 500) : error;
        
        if (this.retryCount >= 3) {
            this.deadLetter = true;
            this.processed = true; 
        }
    }
}
