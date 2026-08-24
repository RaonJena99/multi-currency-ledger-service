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
            AssetType feeAssetType,
            String feeCurrency
    ) {
        /**
         * 입력받은 원시 타입 데이터를 도메인 객체(Money)로 안전하게 변환하는 편의 메서드입니다.
         *
         * <p>금액만 있고 자산 유형/통화가 빠진 <b>부분 입력</b>은 조용히 무시(null 반환)하지 않고
         * 명시적으로 거부합니다. 조용히 무시하면 관리자는 보정이 접수된 줄 알지만(HTTP 200)
         * 실제로는 아무 분개도 만들어지지 않습니다.
         *
         * @return 변환된 수수료 차액 객체 (Money), 수수료 입력이 전혀 없을 경우 null
         * @throws IllegalArgumentException 수수료 입력이 불완전한 경우
         */
        public Money getFeeDifference() {
            boolean amountPresent = feeAmount != null;
            boolean typePresent = feeAssetType != null;
            boolean currencyPresent = feeCurrency != null && !feeCurrency.isBlank();

            if (!amountPresent && !typePresent && !currencyPresent) {
                return null;
            }
            if (!amountPresent || !typePresent || !currencyPresent) {
                throw new IllegalArgumentException(
                        "수수료 보정에는 feeAmount, feeAssetType, feeCurrency 가 모두 필요합니다.");
            }
            // 세 필드가 모두 있는 명시적 0원은 "보정 없음"으로 해석한다.
            if (feeAmount.compareTo(BigDecimal.ZERO) == 0) {
                return null;
            }
            return Money.of(feeAmount, feeAssetType, feeCurrency);
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
