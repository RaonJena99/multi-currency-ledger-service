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

/**
 * 백오피스 관리자(Admin)가 대사 실패 건(Dead Letter)을 수동으로 처리하기 위한 REST API 컨트롤러(Controller)입니다.
 */
@RestController
@RequestMapping("/api/v1/admin/reconciliations")
@RequiredArgsConstructor
public class ReconciliationAdminController {

    private final ManualReconciliationService manualReconciliationService;

    /**
     * 수동 매칭 요청 데이터를 담는 DTO(Data Transfer Object) 레코드입니다.
     */
    public record ManualResolutionRequest(
            UUID internalTransactionId,
            BigDecimal feeAmount,     
            AssetType feeAssetType    
    ) {
        /**
         * 입력받은 원시 타입 데이터를 도메인 객체(Money)로 안전하게 변환하는 편의 메서드입니다.
         * 
         * @return 변환된 수수료 차액 객체 (Money), 없을 경우 null
         */
        public Money getFeeDifference() {
            if (feeAmount == null || feeAmount.compareTo(BigDecimal.ZERO) == 0 || feeAssetType == null) {
                return null; 
            }
            return Money.of(feeAmount.toPlainString(), feeAssetType, "KRW");
        }
    }

    /**
     * 관리자가 데드 레터 건을 특정 내부 트랜잭션과 강제로 매핑하고, 필요 시 보정 수수료를 함께 처리합니다.
     * 
     * @param deadLetterId 처리할 데드 레터의 ID
     * @param request 수동 매칭 및 수수료 보정 정보가 담긴 요청 객체
     * @return 처리 성공 상태 (ResponseEntity<Void>)
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
