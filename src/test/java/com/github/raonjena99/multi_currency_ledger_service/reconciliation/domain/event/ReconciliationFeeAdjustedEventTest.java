package com.github.raonjena99.multi_currency_ledger_service.reconciliation.domain.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.github.raonjena99.multi_currency_ledger_service.common.domain.Money;
import com.github.raonjena99.multi_currency_ledger_service.common.model.AssetType;

class ReconciliationFeeAdjustedEventTest {

    @Test
    void should_create_event_with_valid_parameters() {
        UUID settlementId = UUID.randomUUID();
        UUID tId = UUID.randomUUID();
        UUID aId = UUID.randomUUID();
        Money fee = Money.of("100", AssetType.FIAT, "KRW");

        ReconciliationFeeAdjustedEvent event = ReconciliationFeeAdjustedEvent.of(settlementId, tId, aId, fee);

        assertThat(event.settlementId()).isEqualTo(settlementId);
        assertThat(event.internalTransactionId()).isEqualTo(tId);
        assertThat(event.accountId()).isEqualTo(aId);
        assertThat(event.feeDifference()).isEqualTo(fee);
        assertThat(event.occurredAt()).isNotNull();
    }

    @Test
    void should_throw_if_parameters_are_null() {
        UUID id = UUID.randomUUID();
        Money fee = Money.of("100", AssetType.FIAT, "KRW");

        assertThatThrownBy(() -> new ReconciliationFeeAdjustedEvent(null, id, id, fee, null))
                .isInstanceOf(IllegalArgumentException.class);
        
        assertThatThrownBy(() -> new ReconciliationFeeAdjustedEvent(id, null, id, fee, null))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new ReconciliationFeeAdjustedEvent(id, id, null, fee, null))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new ReconciliationFeeAdjustedEvent(id, id, id, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
    
    @Test
    void should_assign_occurredAt_if_null() {
        UUID id = UUID.randomUUID();
        Money fee = Money.of("100", AssetType.FIAT, "KRW");
        
        ReconciliationFeeAdjustedEvent event = new ReconciliationFeeAdjustedEvent(id, id, id, fee, null);
        assertThat(event.occurredAt()).isNotNull();
    }
}
