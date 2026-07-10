package com.github.raonjena99.multi_currency_ledger_service.reconciliation.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class ExternalSettlementIdTest {

    @Test
    void equalsAndHashCode() {
        UUID id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        ExternalSettlementId id1 = new ExternalSettlementId(id, now);
        ExternalSettlementId id2 = new ExternalSettlementId(id, now);
        ExternalSettlementId id3 = new ExternalSettlementId(UUID.randomUUID(), now);
        
        assertThat(id1).isEqualTo(id1);
        assertThat(id1).isEqualTo(id2);
        assertThat(id1).isNotEqualTo(id3);
        assertThat(id1).isNotEqualTo(null);
        assertThat(id1).isNotEqualTo(new Object());
        assertThat(id1.hashCode()).isEqualTo(id2.hashCode());
    }
}
