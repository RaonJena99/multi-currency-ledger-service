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

    public void markAsResolved() {
        if (this.isResolved) {
            throw new IllegalStateException("This dead letter has already been resolved.");
        }
        this.isResolved = true;
        this.resolvedAt = OffsetDateTime.now();
    }
}
