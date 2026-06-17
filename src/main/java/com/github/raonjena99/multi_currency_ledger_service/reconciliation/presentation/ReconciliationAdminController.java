package com.github.raonjena99.multi_currency_ledger_service.reconciliation.presentation;

import java.math.BigDecimal;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.github.raonjena99.multi_currency_ledger_service.common.domain.Money;
import com.github.raonjena99.multi_currency_ledger_service.common.model.AssetType;
import com.github.raonjena99.multi_currency_ledger_service.reconciliation.application.service.ManualReconciliationService;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/admin/reconciliations")
@RequiredArgsConstructor
public class ReconciliationAdminController {

    private final ManualReconciliationService manualReconciliationService;

    public record ManualResolutionRequest(
            UUID internalTransactionId,
            BigDecimal feeAmount,     
            AssetType feeAssetType    
    ) {
        /**
         * 입력받은 원시 타입 데이터를 도메인 객체(Money)로 안전하게 변환하는 편의 메서드
         */
        public Money getFeeDifference() {
            if (feeAmount == null || feeAmount.compareTo(BigDecimal.ZERO) == 0 || feeAssetType == null) {
                return null; 
            }
            return Money.of(feeAmount.toPlainString(), feeAssetType);
        }
    }

    /**
     * 관리자가 데드 레터 건을 특정 내부 트랜잭션과 강제로 맵핑하고, 필요시 보정 수수료를 함께 처리
     */
    @PostMapping("/dead-letters/{deadLetterId}/resolve")
    public ResponseEntity<Void> resolveDeadLetter(
            @PathVariable Long deadLetterId,
            @RequestBody ManualResolutionRequest request) {
        
        manualReconciliationService.resolveManually(
                deadLetterId, 
                request.internalTransactionId(), 
                request.getFeeDifference()
        );
        
        return ResponseEntity.ok().build();
    }
}
