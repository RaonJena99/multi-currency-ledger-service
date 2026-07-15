package com.github.raonjena99.multi_currency_ledger_service.reconciliation.domain;

import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

import org.springframework.data.domain.Persistable;

import com.github.raonjena99.multi_currency_ledger_service.common.domain.BaseEntity;
import com.github.raonjena99.multi_currency_ledger_service.common.domain.Money;
import com.github.raonjena99.multi_currency_ledger_service.common.exception.InvalidSettlementStateException;
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
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 외부 시스템(예: PG사, 은행 등)으로부터 수신한 정산 내역을 저장하는 엔티티(Entity) 클래스입니다.
 */
@Entity
@Table(name = "external_settlement", 
    indexes = {
        @Index(name = "idx_settlement_date_status", columnList = "settlement_date, status"),
        @Index(name = "idx_external_ref_id", columnList = "external_reference_id")
    },
    uniqueConstraints = {
        @UniqueConstraint(
            name = "uk_external_ref_id_settlement_date", 
            columnNames = {"external_reference_id", "settlement_date"}
        )
    }
)
@IdClass(ExternalSettlementId.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ExternalSettlement extends BaseEntity implements Persistable<UUID> {

    @Id
    @Column(name = "id", length = 36)
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
    private UUID matchedInternalTransactionId;

    /**
     * 새로운 외부 정산 내역 엔티티를 생성합니다. 초기 상태는 대기(PENDING)로 설정됩니다.
     * 
     * @param externalReferenceId 외부 시스템에서 부여한 고유 참조 ID
     * @param institutionCode 기관(PG사 등) 코드
     * @param settlementDate 정산 발생 일시
     * @param description 정산 적요/내역
     * @param amount 정산 금액 (Money)
     * @return 생성된 외부 정산 엔티티 (ExternalSettlement)
     */
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
     * 내부 거래와 성공적으로 매칭된 경우 상태를 일치(MATCHED)로 변경하고 매칭된 내부 거래 ID를 기록합니다.
     * 
     * @param internalTransactionId 매칭된 내부 거래 ID (UUID)
     */
    public void markAsMatched(UUID internalTransactionId) {
        if (this.status != SettlementStatus.PENDING && this.status != SettlementStatus.UNMATCHED) {
            throw new InvalidSettlementStateException("Only settlements in PENDING or UNMATCHED state can transition to MATCHED.");
        }
        this.status = SettlementStatus.MATCHED;
        this.matchedInternalTransactionId = Objects.requireNonNull(internalTransactionId);
    }

    /**
     * 대기(PENDING) 상태에서 자동 대사에 실패한 경우, 불일치(UNMATCHED) 상태로 변경합니다.
     */
    public void markAsUnmatched() {
        if (this.status != SettlementStatus.PENDING) {
            throw new InvalidSettlementStateException("Only settlements in PENDING state can transition to UNMATCHED.");
        }
        this.status = SettlementStatus.UNMATCHED;
    }

    /**
     * 불일치(UNMATCHED) 상태인 건을 관리자가 특정 내부 거래와 수동으로 강제 매핑하여 해결(MANUALLY_RESOLVED) 상태로 변경합니다.
     * 
     * @param internalTransactionId 수동으로 매칭할 내부 거래 ID (UUID)
     */
    public void resolveManually(UUID internalTransactionId) {
        if (this.status != SettlementStatus.UNMATCHED) {
            throw new InvalidSettlementStateException("Only specifications in UNMATCHED state can be manually resolved.");
        }
        this.status = SettlementStatus.MANUALLY_RESOLVED;
        this.matchedInternalTransactionId = Objects.requireNonNull(internalTransactionId);
    }

    @Override
    public boolean isNew() {
        return this.getCreatedAt() == null;
    }
}
