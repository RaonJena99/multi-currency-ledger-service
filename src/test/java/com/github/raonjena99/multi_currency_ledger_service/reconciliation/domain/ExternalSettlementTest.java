package com.github.raonjena99.multi_currency_ledger_service.reconciliation.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.github.raonjena99.multi_currency_ledger_service.common.domain.Money;
import com.github.raonjena99.multi_currency_ledger_service.common.exception.InvalidSettlementStateException;
import com.github.raonjena99.multi_currency_ledger_service.common.model.AssetType;
import com.github.raonjena99.multi_currency_ledger_service.common.model.SettlementStatus;

class ExternalSettlementTest {

    @Test
    void create_should_initialize_status_as_pending() {
        ExternalSettlement settlement = ExternalSettlement.create(
            "ext-1", "PG1", OffsetDateTime.now(), "Desc", Money.of("10", AssetType.FIAT, "USD")
        );
        assertThat(settlement.getStatus()).isEqualTo(SettlementStatus.PENDING);
        assertThat(settlement.isNew()).isTrue();
    }

    @Test
    void markAsMatched_should_transition_from_pending_or_unmatched() {
        ExternalSettlement settlement = ExternalSettlement.create(
            "ext-1", "PG1", OffsetDateTime.now(), "Desc", Money.of("10", AssetType.FIAT, "USD")
        );
        
        UUID internalId = UUID.randomUUID();
        settlement.markAsMatched(internalId);
        
        assertThat(settlement.getStatus()).isEqualTo(SettlementStatus.MATCHED);
        assertThat(settlement.getMatchedInternalTransactionId()).isEqualTo(internalId);

        // From UNMATCHED
        ExternalSettlement unmatched = ExternalSettlement.create(
            "ext-2", "PG1", OffsetDateTime.now(), "Desc", Money.of("10", AssetType.FIAT, "USD")
        );
        unmatched.markAsUnmatched();
        unmatched.markAsMatched(internalId);
        assertThat(unmatched.getStatus()).isEqualTo(SettlementStatus.MATCHED);
    }

    @Test
    void markAsMatched_should_throw_if_invalid_state() {
        ExternalSettlement settlement = ExternalSettlement.create(
            "ext-1", "PG1", OffsetDateTime.now(), "Desc", Money.of("10", AssetType.FIAT, "USD")
        );
        settlement.markAsMatched(UUID.randomUUID());

        // Already matched
        assertThatThrownBy(() -> settlement.markAsMatched(UUID.randomUUID()))
            .isInstanceOf(InvalidSettlementStateException.class);
    }

    @Test
    void markAsUnmatched_should_transition_from_pending() {
        ExternalSettlement settlement = ExternalSettlement.create(
            "ext-1", "PG1", OffsetDateTime.now(), "Desc", Money.of("10", AssetType.FIAT, "USD")
        );
        settlement.markAsUnmatched();
        
        assertThat(settlement.getStatus()).isEqualTo(SettlementStatus.UNMATCHED);
    }

    @Test
    void markAsUnmatched_should_throw_if_invalid_state() {
        ExternalSettlement settlement = ExternalSettlement.create(
            "ext-1", "PG1", OffsetDateTime.now(), "Desc", Money.of("10", AssetType.FIAT, "USD")
        );
        settlement.markAsMatched(UUID.randomUUID());

        assertThatThrownBy(() -> settlement.markAsUnmatched())
            .isInstanceOf(InvalidSettlementStateException.class);
    }

    @Test
    void resolveManually_should_transition_from_unmatched() {
        ExternalSettlement settlement = ExternalSettlement.create(
            "ext-1", "PG1", OffsetDateTime.now(), "Desc", Money.of("10", AssetType.FIAT, "USD")
        );
        settlement.markAsUnmatched();
        
        UUID internalId = UUID.randomUUID();
        settlement.resolveManually(internalId);
        
        assertThat(settlement.getStatus()).isEqualTo(SettlementStatus.MANUALLY_RESOLVED);
        assertThat(settlement.getMatchedInternalTransactionId()).isEqualTo(internalId);
    }

    @Test
    void resolveManually_should_throw_if_invalid_state() {
        ExternalSettlement settlement = ExternalSettlement.create(
            "ext-1", "PG1", OffsetDateTime.now(), "Desc", Money.of("10", AssetType.FIAT, "USD")
        );

        // From PENDING
        assertThatThrownBy(() -> settlement.resolveManually(UUID.randomUUID()))
            .isInstanceOf(InvalidSettlementStateException.class);
    }
}
