package com.github.raonjena99.multi_currency_ledger_service.reconciliation.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class ExternalSettlementIdTest {

    @Test
    void equalsAndHashCode() {
        UUID id = UUID.randomUUID();
        OffsetDateTime date = OffsetDateTime.now();
        
        ExternalSettlementId id1 = new ExternalSettlementId(id, date);
        ExternalSettlementId id2 = new ExternalSettlementId(id, date);
        ExternalSettlementId diffId = new ExternalSettlementId(UUID.randomUUID(), date);
        ExternalSettlementId diffDate = new ExternalSettlementId(id, date.plusDays(1));
        
        // Use a subtype to test getClass() != o.getClass() branch
        class SubSettlementId extends ExternalSettlementId {
            SubSettlementId() { super(); }
        }

        assertThat(id1.equals(id1)).isTrue();
        assertThat(id1.equals(null)).isFalse();
        assertThat(id1.equals(new Object())).isFalse();
        assertThat(id1.equals(new SubSettlementId())).isFalse();
        assertThat(id1.equals(id2)).isTrue();
        assertThat(id1.equals(diffId)).isFalse();
        assertThat(id1.equals(diffDate)).isFalse();
        assertThat(id1.hashCode()).isEqualTo(id2.hashCode());
    }
}
