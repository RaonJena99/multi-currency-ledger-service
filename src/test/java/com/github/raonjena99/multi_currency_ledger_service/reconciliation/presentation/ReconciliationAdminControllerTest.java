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
            new ReconciliationAdminController.ManualResolutionRequest(txId, BigDecimal.TEN, AssetType.FIAT, "KRW");
        
        ResponseEntity<Void> res = controller.resolveDeadLetter(1L, req);
        
        verify(manualReconciliationService).resolveManually(eq(1L), eq(txId), any());
        org.assertj.core.api.Assertions.assertThat(res.getStatusCode().is2xxSuccessful()).isTrue();
    }
    
    @Test
    void resolveDeadLetter_nullFee() {
        UUID txId = UUID.randomUUID();
        // 수수료 입력이 전혀 없는 경우: 보정 없이 정상 처리된다.
        ReconciliationAdminController.ManualResolutionRequest req =
            new ReconciliationAdminController.ManualResolutionRequest(txId, null, null, null);
        
        ResponseEntity<Void> res = controller.resolveDeadLetter(1L, req);
        
        verify(manualReconciliationService).resolveManually(eq(1L), eq(txId), any());
        org.assertj.core.api.Assertions.assertThat(res.getStatusCode().is2xxSuccessful()).isTrue();
    }

    @Test
    void manualResolutionRequest_getFeeDifference() {
        UUID txId = UUID.randomUUID();
        
        // 수수료 입력이 전혀 없으면 보정 없음(null)
        org.assertj.core.api.Assertions.assertThat(new ReconciliationAdminController.ManualResolutionRequest(txId, null, null, null).getFeeDifference()).isNull();
        // 부분 입력은 조용히 무시하지 않고 명시적으로 거부한다. 조용히 null 을 돌려주면
        // 관리자는 보정이 접수된 줄 알지만(HTTP 200) 실제로는 아무 분개도 만들어지지 않는다.
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new ReconciliationAdminController.ManualResolutionRequest(txId, null, AssetType.FIAT, "KRW").getFeeDifference())
                .isInstanceOf(IllegalArgumentException.class);
        // 세 필드가 모두 있는 명시적 0원은 "보정 없음"(null)으로 해석한다.
        org.assertj.core.api.Assertions.assertThat(new ReconciliationAdminController.ManualResolutionRequest(txId, BigDecimal.ZERO, AssetType.FIAT, "KRW").getFeeDifference()).isNull();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new ReconciliationAdminController.ManualResolutionRequest(txId, BigDecimal.TEN, null, "KRW").getFeeDifference())
                .isInstanceOf(IllegalArgumentException.class);
        // all present
        org.assertj.core.api.Assertions.assertThat(new ReconciliationAdminController.ManualResolutionRequest(txId, BigDecimal.TEN, AssetType.FIAT, "KRW").getFeeDifference()).isNotNull();
    }
}
