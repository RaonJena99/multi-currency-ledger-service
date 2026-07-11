package com.github.raonjena99.multi_currency_ledger_service.reconciliation.domain;

import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * ExternalSettlement(외부 정산) 엔티티의 복합 키(Composite Key)를 정의하는 클래스입니다.
 * 식별자(UUID)와 정산 일자(settlementDate)를 조합하여 사용합니다.
 */
public class ExternalSettlementId implements Serializable {
    private UUID id;
    private OffsetDateTime settlementDate;

    protected ExternalSettlementId() {}

    /**
     * 복합 키를 초기화하는 생성자입니다.
     * 
     * @param id 정산 데이터 식별자 (UUID)
     * @param settlementDate 정산 일자 (OffsetDateTime)
     */
    public ExternalSettlementId(UUID id, OffsetDateTime settlementDate) {
        this.id = id;
        this.settlementDate = settlementDate;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ExternalSettlementId that = (ExternalSettlementId) o;
        return Objects.equals(id, that.id) && 
                Objects.equals(settlementDate, that.settlementDate);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, settlementDate);
    }
}
