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

    private org.springframework.test.web.servlet.MockMvc mockMvc() {
        return org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new com.github.raonjena99.multi_currency_ledger_service.common.exception.GlobalExceptionHandler())
                .build();
    }

    @Test
    void resolveDeadLetter_should_reject_extreme_fee_exponent_before_rounding() throws Exception {
        // 1e999999999 를 자산 단위로 반올림하면 거대한 정수를 만들며 스레드가 멈춘다. 검증에서 먼저 막아야 한다.
        String body = "{\"internalTransactionId\":\"" + UUID.randomUUID()
                + "\",\"feeAmount\":1e999999999,\"feeAssetType\":\"FIAT\",\"feeCurrency\":\"KRW\"}";

        mockMvc().perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/v1/admin/reconciliations/dead-letters/{id}/resolve", 1L)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
        org.mockito.Mockito.verifyNoInteractions(manualReconciliationService);
    }

    @Test
    void resolveDeadLetter_should_reject_missing_internal_transaction_id() throws Exception {
        String body = "{\"feeAmount\":10,\"feeAssetType\":\"FIAT\",\"feeCurrency\":\"KRW\"}";

        mockMvc().perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/v1/admin/reconciliations/dead-letters/{id}/resolve", 1L)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
        org.mockito.Mockito.verifyNoInteractions(manualReconciliationService);
    }
}
