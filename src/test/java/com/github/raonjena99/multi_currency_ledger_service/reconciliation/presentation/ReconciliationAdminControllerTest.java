package com.github.raonjena99.multi_currency_ledger_service.reconciliation.presentation;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import com.github.raonjena99.multi_currency_ledger_service.common.model.AssetType;
import com.github.raonjena99.multi_currency_ledger_service.reconciliation.application.service.ManualReconciliationService;

@ExtendWith(MockitoExtension.class)
class ReconciliationAdminControllerTest {
    @Mock private ManualReconciliationService manualReconciliationService;
    @InjectMocks private ReconciliationAdminController controller;

    @Test
    void resolveDeadLetter() {
        UUID txId = UUID.randomUUID();
        ReconciliationAdminController.ManualResolutionRequest req = 
            new ReconciliationAdminController.ManualResolutionRequest(txId, BigDecimal.TEN, AssetType.FIAT);
        
        ResponseEntity<Void> res = controller.resolveDeadLetter(1L, req);
        
        verify(manualReconciliationService).resolveManually(eq(1L), eq(txId), any());
        org.assertj.core.api.Assertions.assertThat(res.getStatusCode().is2xxSuccessful()).isTrue();
    }
    
    @Test
    void resolveDeadLetter_nullFee() {
        UUID txId = UUID.randomUUID();
        ReconciliationAdminController.ManualResolutionRequest req = 
            new ReconciliationAdminController.ManualResolutionRequest(txId, null, AssetType.FIAT);
        
        ResponseEntity<Void> res = controller.resolveDeadLetter(1L, req);
        
        verify(manualReconciliationService).resolveManually(eq(1L), eq(txId), any());
        org.assertj.core.api.Assertions.assertThat(res.getStatusCode().is2xxSuccessful()).isTrue();
    }

    @Test
    void manualResolutionRequest_getFeeDifference() {
        UUID txId = UUID.randomUUID();
        
        // feeAmount == null
        org.assertj.core.api.Assertions.assertThat(new ReconciliationAdminController.ManualResolutionRequest(txId, null, AssetType.FIAT).getFeeDifference()).isNull();
        // feeAmount == 0
        org.assertj.core.api.Assertions.assertThat(new ReconciliationAdminController.ManualResolutionRequest(txId, BigDecimal.ZERO, AssetType.FIAT).getFeeDifference()).isNull();
        // feeAssetType == null
        org.assertj.core.api.Assertions.assertThat(new ReconciliationAdminController.ManualResolutionRequest(txId, BigDecimal.TEN, null).getFeeDifference()).isNull();
        // all present
        org.assertj.core.api.Assertions.assertThat(new ReconciliationAdminController.ManualResolutionRequest(txId, BigDecimal.TEN, AssetType.FIAT).getFeeDifference()).isNotNull();
    }
}
