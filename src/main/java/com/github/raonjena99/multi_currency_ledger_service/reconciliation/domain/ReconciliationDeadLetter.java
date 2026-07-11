package com.github.raonjena99.multi_currency_ledger_service.reconciliation.domain;

import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.github.raonjena99.multi_currency_ledger_service.common.domain.BaseEntity;
import com.github.raonjena99.multi_currency_ledger_service.common.model.FailureReason;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 자동 대사(Reconciliation) 과정에서 매칭에 실패하거나 예외가 발생한 항목들을
 * 별도로 격리 보관하는 데드 레터(Dead Letter) 엔티티 클래스입니다.
 */
@Entity
@Table(name = "reconciliation_dead_letter", indexes = {
    @Index(name = "idx_dlq_reason", columnList = "failure_reason"),
    @Index(name = "idx_dlq_resolved", columnList = "is_resolved")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReconciliationDeadLetter extends BaseEntity{

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "external_settlement_id", nullable = false, length = 36)
    private UUID externalSettlementId;

    @Enumerated(EnumType.STRING)
    @Column(name = "failure_reason", nullable = false, length = 30)
    private FailureReason failureReason;

    @Column(name = "error_message", nullable = false, length = 500)
    private String errorMessage;

    @Column(name = "is_resolved", nullable = false)
    private boolean isResolved;

    @Column(name = "resolved_at", columnDefinition = "TIMESTAMPTZ")
    private OffsetDateTime resolvedAt;

    @Column(name = "handler_enrichment_payload", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String handlerEnrichmentPayload;

    /**
     * 실패한 외부 정산 내역을 데드 레터로 격리하기 위해 새로운 엔티티를 생성합니다.
     * 
     * @param externalSettlementId 실패한 외부 정산 ID (UUID)
     * @param reason 실패 사유 (FailureReason)
     * @param errorMessage 에러 상세 메시지
     * @param payload 추가적인 상태 정보나 스냅샷 데이터 (JSON)
     * @return 생성된 데드 레터 엔티티 (ReconciliationDeadLetter)
     */
    public static ReconciliationDeadLetter isolate(UUID externalSettlementId, FailureReason reason, 
                                                    String errorMessage, String payload) {
        ReconciliationDeadLetter deadLetter = new ReconciliationDeadLetter();
        deadLetter.externalSettlementId = Objects.requireNonNull(externalSettlementId);
        deadLetter.failureReason = Objects.requireNonNull(reason);
        deadLetter.errorMessage = Objects.requireNonNull(errorMessage);
        deadLetter.handlerEnrichmentPayload = payload;
        deadLetter.isResolved = false;
        return deadLetter;
    }

    /**
     * 관리자가 수동으로 문제를 해결(Resolved)한 상태로 전이시킵니다.
     * 해결 일시(resolvedAt)가 현재 시간으로 기록됩니다.
     */
    public void markAsResolved() {
        if (this.isResolved) {
            throw new IllegalStateException("This dead letter has already been resolved.");
        }
        this.isResolved = true;
        this.resolvedAt = OffsetDateTime.now();
    }
}
