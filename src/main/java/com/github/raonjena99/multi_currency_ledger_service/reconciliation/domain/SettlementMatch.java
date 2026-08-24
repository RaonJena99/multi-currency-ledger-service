package com.github.raonjena99.multi_currency_ledger_service.reconciliation.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.springframework.data.domain.Persistable;

import com.github.raonjena99.multi_currency_ledger_service.common.domain.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 외부 정산과 내부 거래의 1:1 매칭 관계를 DB 수준에서 강제하는 엔티티입니다.
 *
 * <p>{@code external_settlement} 는 {@code settlement_date} 로 파티션된 테이블이라 파티션 키를
 * 포함하지 않는 전역 유니크 제약을 만들 수 없습니다. 그래서 매칭 관계만 비파티션 테이블로 분리해
 * {@code internal_transaction_id} 를 PK 로 두었습니다. 이제 같은 내부 거래를 두 정산에 매칭하려는
 * 시도는 애플리케이션 검사와 무관하게 DB 가 거부합니다.
 */
@Entity
@Table(name = "settlement_match")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SettlementMatch extends BaseEntity implements Persistable<UUID> {

    @Id
    @Column(name = "internal_transaction_id", nullable = false)
    private UUID internalTransactionId;

    @Column(name = "external_settlement_id", nullable = false)
    private UUID externalSettlementId;

    @Column(name = "settlement_date", nullable = false, columnDefinition = "TIMESTAMPTZ")
    private OffsetDateTime settlementDate;

    @Column(name = "matched_at", nullable = false, columnDefinition = "TIMESTAMPTZ")
    private OffsetDateTime matchedAt;

    /**
     * 매칭 관계를 생성합니다.
     *
     * @param internalTransactionId 내부 거래 ID
     * @param externalSettlementId  외부 정산 ID
     * @param settlementDate        외부 정산 일시 (파티션 키)
     * @return 생성된 매칭 엔티티
     */
    public static SettlementMatch of(UUID internalTransactionId, UUID externalSettlementId,
                                     OffsetDateTime settlementDate) {
        SettlementMatch match = new SettlementMatch();
        match.internalTransactionId = internalTransactionId;
        match.externalSettlementId = externalSettlementId;
        match.settlementDate = settlementDate;
        match.matchedAt = OffsetDateTime.now();
        return match;
    }

    @Transient
    private boolean isNew = true;

    @PostPersist
    @PostLoad
    protected void markNotNew() {
        this.isNew = false;
    }

    @Override
    public UUID getId() {
        return this.internalTransactionId;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }
}
