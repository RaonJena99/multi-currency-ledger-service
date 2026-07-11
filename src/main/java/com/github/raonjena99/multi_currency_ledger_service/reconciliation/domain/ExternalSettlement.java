package com.github.raonjena99.multi_currency_ledger_service.reconciliation.domain;

import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.github.raonjena99.multi_currency_ledger_service.common.domain.BaseEntity;
import com.github.raonjena99.multi_currency_ledger_service.common.domain.Money;
import com.github.raonjena99.multi_currency_ledger_service.common.model.SettlementStatus;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "external_settlement", indexes = {
    @Index(name = "idx_settlement_date_status", columnList = "settlement_date, status"),
    @Index(name = "idx_external_ref_id", columnList = "external_reference_id")
})
@IdClass(ExternalSettlementId.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ExternalSettlement extends BaseEntity {

    @Id
    @Column(name = "id", length = 36)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    private UUID id;

    @Id
    @Column(name = "settlement_date", nullable = false)
    private OffsetDateTime settlementDate;

    @Column(name = "external_reference_id", nullable = false, length = 100)
    private String externalReferenceId;

    @Column(name = "institution_code", nullable = false, length = 20)
    private String institutionCode;

    @Column(name = "description", nullable = false, length = 255)
    private String description;

    @Embedded
    @AttributeOverrides({
        @AttributeOverride(name = "amount", column = @Column(name = "amount", precision = 36, scale = 18, nullable = false)),
        @AttributeOverride(name = "assetType", column = @Column(name = "asset_type", length = 20, nullable = false)),
        @AttributeOverride(name = "currencyCode", column = @Column(name = "currency_code", length = 10, nullable = false))
    })
    private Money amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private SettlementStatus status;

    @Column(name = "matched_internal_transaction_id", length = 36)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    private UUID matchedInternalTransactionId;

    public static ExternalSettlement create(String externalReferenceId, String institutionCode, 
                                            OffsetDateTime settlementDate, String description, Money amount) {
        ExternalSettlement settlement = new ExternalSettlement();
        settlement.id = UUID.randomUUID();
        settlement.externalReferenceId = Objects.requireNonNull(externalReferenceId);
        settlement.institutionCode = Objects.requireNonNull(institutionCode);
        settlement.settlementDate = Objects.requireNonNull(settlementDate);
        settlement.description = Objects.requireNonNull(description);
        settlement.amount = Objects.requireNonNull(amount);
        settlement.status = SettlementStatus.PENDING;
        return settlement;
    }

    /**
     * 내부 거래 ID를 매핑하고 일치(MATCHED) 상태로 변경
     */
    public void markAsMatched(UUID internalTransactionId) {
        if (this.status != SettlementStatus.PENDING && this.status != SettlementStatus.UNMATCHED) {
            throw new IllegalStateException("Only specifications in PENDING or UNMATCHED state can transition to MATCHED.");
        }
        this.status = SettlementStatus.MATCHED;
        this.matchedInternalTransactionId = Objects.requireNonNull(internalTransactionId);
    }

    /**
     * 대기(PENDING) 상태인 경우, 불일치(UNMATCHED) 상태로 변경
     */
    public void markAsUnmatched() {
        if (this.status != SettlementStatus.PENDING) {
            throw new IllegalStateException("Only specifications in PENDING state can transition to UNMATCHED.");
        }
        this.status = SettlementStatus.UNMATCHED;
    }

    /**
     * 불일치(UNMATCHED) 상태인 경우, 내부 거래 ID를 매핑하고 수동 해제(MANUALLY_RESOLVED) 상태로 변경
     */
    public void resolveManually(UUID internalTransactionId) {
        if (this.status != SettlementStatus.UNMATCHED) {
            throw new IllegalStateException("Only specifications in UNMATCHED state can be manually resolved.");
        }
        this.status = SettlementStatus.MANUALLY_RESOLVED;
        this.matchedInternalTransactionId = Objects.requireNonNull(internalTransactionId);
    }
}
