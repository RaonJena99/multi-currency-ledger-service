package com.github.raonjena99.multi_currency_ledger_service.reconciliation.presentation;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.github.raonjena99.multi_currency_ledger_service.common.model.AssetType;
import com.github.raonjena99.multi_currency_ledger_service.reconciliation.presentation.ReconciliationAdminController.ManualResolutionRequest;

class ReconciliationAdminControllerTest {

    @Test
    void manualResolutionRequest_getFeeDifference() {
        ManualResolutionRequest req1 = new ManualResolutionRequest(UUID.randomUUID(), null, AssetType.FIAT);
        assertThat(req1.getFeeDifference()).isNull();

        ManualResolutionRequest req2 = new ManualResolutionRequest(UUID.randomUUID(), BigDecimal.ZERO, AssetType.FIAT);
        assertThat(req2.getFeeDifference()).isNull();

        ManualResolutionRequest req3 = new ManualResolutionRequest(UUID.randomUUID(), BigDecimal.TEN, null);
        assertThat(req3.getFeeDifference()).isNull();

        ManualResolutionRequest req4 = new ManualResolutionRequest(UUID.randomUUID(), BigDecimal.TEN, AssetType.FIAT);
        assertThat(req4.getFeeDifference()).isNotNull();
        assertThat(req4.getFeeDifference().getAmount()).isEqualByComparingTo(BigDecimal.TEN);
    }
}
