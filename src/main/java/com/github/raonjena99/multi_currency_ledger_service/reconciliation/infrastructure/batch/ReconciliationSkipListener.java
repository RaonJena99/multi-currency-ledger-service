package com.github.raonjena99.multi_currency_ledger_service.reconciliation.infrastructure.batch;

import org.springframework.batch.core.listener.SkipListener;
import org.springframework.stereotype.Component;

import com.github.raonjena99.multi_currency_ledger_service.common.model.FailureReason;
import com.github.raonjena99.multi_currency_ledger_service.reconciliation.application.batch.MatchedReconciliationResult;
import com.github.raonjena99.multi_currency_ledger_service.reconciliation.application.exception.UnmatchableSettlementException;
import com.github.raonjena99.multi_currency_ledger_service.reconciliation.domain.ExternalSettlement;
import com.github.raonjena99.multi_currency_ledger_service.reconciliation.domain.ReconciliationDeadLetter;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class ReconciliationSkipListener implements SkipListener<ExternalSettlement, MatchedReconciliationResult> {

    private final EntityManager entityManager;

    @Override 
    public void onSkipInProcess(ExternalSettlement item, Throwable t) {
        UnmatchableSettlementException targetException = extractTargetException(t);

        if (targetException != null) {
            String errorMessage = targetException.getMessage() != null ? targetException.getMessage() : "UNKNOWN_ERROR";
            FailureReason reason = determineFailureReason(errorMessage);
            String descriptionSnapshot = item.getDescription() != null ? item.getDescription() : "";
            
            // 데드 레터 엔티티 생성 및 저장
            ReconciliationDeadLetter dlq = ReconciliationDeadLetter.isolate(
                    item.getId(),
                    reason,
                    errorMessage,
                    "{\"description_snapshot\": \"" + descriptionSnapshot + "\"}"
            );
            
            // 엔티티 상태 변경
            item.markAsUnmatched();
            
            // 컨텍스트 동기화 유도
            if (!entityManager.contains(item)) {
                entityManager.merge(item);
            }
            
            entityManager.persist(dlq);
            
            log.warn("[DLQ 격리 완료] 명세 ID: {}, 사유: {}", item.getId(), reason);
        } else {
            log.error("[DLQ 격리 실패] 식별할 수 없는 예외가 발생하여 건너뜁니다. 명세 ID: {}, 원인: {}", item.getId(), t.getMessage(), t);
        }
    }

    private UnmatchableSettlementException extractTargetException(Throwable t) {
        Throwable cause = t;
        while (cause != null) {
            if (cause instanceof UnmatchableSettlementException) {
                return (UnmatchableSettlementException) cause;
            }
            cause = cause.getCause();
        }
        return null;
    }

    private FailureReason determineFailureReason(String message) {
        if (message == null) return FailureReason.SYSTEM_ERROR;
        if (message.contains("TIME_WINDOW_EXCEEDED")) return FailureReason.TIME_WINDOW_EXCEEDED;
        if (message.contains("AMOUNT_MISMATCH")) return FailureReason.AMOUNT_MISMATCH;
        if (message.contains("TEXT_NOT_FOUND")) return FailureReason.TEXT_NOT_FOUND;
        return FailureReason.SYSTEM_ERROR;
    }
}