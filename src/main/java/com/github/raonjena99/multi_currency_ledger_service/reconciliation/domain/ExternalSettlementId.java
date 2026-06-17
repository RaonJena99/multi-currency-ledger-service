package com.github.raonjena99.multi_currency_ledger_service.reconciliation.domain;

import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

public class ExternalSettlementId implements Serializable {
    private UUID id;
    private OffsetDateTime settlementDate;

    protected ExternalSettlementId() {}

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
