package com.github.raonjena99.multi_currency_ledger_service.transaction.domain;

import java.time.OffsetDateTime;

import com.github.raonjena99.multi_currency_ledger_service.common.domain.BaseEntity;

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
 * 복식부기 원장 기록이 완전히 실패해 DLT 로 넘어간 메시지를 격리 보관하는 엔티티입니다.
 *
 * <p>이 기록이 존재한다는 것은 <b>잔고는 이미 변경되었는데 대응하는 분개가 없다</b>는 뜻입니다.
 * 반드시 운영자의 보상 처리가 필요한 상태이므로, 로그로 흘려보내지 않고 조회 가능한 형태로 남깁니다.
 */
@Entity
@Table(name = "ledger_dead_letters")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LedgerDeadLetter extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "original_topic", nullable = false, length = 255)
    private String originalTopic;

    @Column(name = "error_message", length = 2000)
    private String errorMessage;

    @Column(name = "payload", columnDefinition = "TEXT", nullable = false)
    private String payload;

    @Column(name = "correlation_id", length = 100)
    private String correlationId;

    @Column(name = "is_resolved", nullable = false)
    private boolean isResolved;

    @Column(name = "resolved_at", columnDefinition = "TIMESTAMPTZ")
    private OffsetDateTime resolvedAt;

    /**
     * 원장 기록 실패 건을 격리합니다.
     *
     * @param originalTopic 원본 토픽 이름
     * @param errorMessage  실패 원인 메시지
     * @param payload       처리에 실패한 원본 페이로드
     * @param correlationId 분산 추적 식별자
     * @return 생성된 격리 기록
     */
    public static LedgerDeadLetter isolate(String originalTopic, String errorMessage,
                                           String payload, String correlationId) {
        LedgerDeadLetter deadLetter = new LedgerDeadLetter();
        deadLetter.originalTopic = originalTopic != null ? originalTopic : "UNKNOWN";
        deadLetter.errorMessage = truncate(errorMessage, 2000);
        deadLetter.payload = payload != null ? payload : "";
        deadLetter.correlationId = truncate(correlationId, 100);
        deadLetter.isResolved = false;
        return deadLetter;
    }

    /**
     * 운영자가 보상 처리를 완료했음을 기록합니다.
     */
    public void markAsResolved() {
        if (this.isResolved) {
            throw new IllegalStateException("This ledger dead letter has already been resolved.");
        }
        this.isResolved = true;
        this.resolvedAt = OffsetDateTime.now();
    }

    private static String truncate(String value, int maxLength) {
        if (value == null) return null;
        return value.length() > maxLength ? value.substring(0, maxLength) : value;
    }
}
