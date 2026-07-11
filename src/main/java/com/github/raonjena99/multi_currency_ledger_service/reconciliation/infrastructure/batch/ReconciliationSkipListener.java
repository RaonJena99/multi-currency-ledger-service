package com.github.raonjena99.multi_currency_ledger_service.reconciliation.infrastructure.batch;

import org.springframework.batch.core.listener.SkipListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.github.raonjena99.multi_currency_ledger_service.common.model.FailureReason;
import com.github.raonjena99.multi_currency_ledger_service.reconciliation.application.batch.MatchedReconciliationResult;
import com.github.raonjena99.multi_currency_ledger_service.reconciliation.application.exception.UnmatchableSettlementException;
import com.github.raonjena99.multi_currency_ledger_service.reconciliation.domain.ExternalSettlement;
import com.github.raonjena99.multi_currency_ledger_service.reconciliation.domain.ExternalSettlementId;
import com.github.raonjena99.multi_currency_ledger_service.reconciliation.domain.ReconciliationDeadLetter;
import com.github.raonjena99.multi_currency_ledger_service.reconciliation.infrastructure.ExternalSettlementRepository;
import com.github.raonjena99.multi_currency_ledger_service.reconciliation.ReconciliationDeadLetterRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Spring Batch 실행 중 대사 처리에 실패하여 예외가 발생했을 때 호출되는 스킵 리스너(SkipListener)입니다.
 * 실패 건을 데드 레터(ReconciliationDeadLetter) 큐(DLQ)로 격리하고 상태를 UNMATCHED로 변경합니다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReconciliationSkipListener implements SkipListener<ExternalSettlement, MatchedReconciliationResult> {

    private final ExternalSettlementRepository settlementRepository;
    private final ReconciliationDeadLetterRepository deadLetterRepository;

    /**
     * ItemProcessor에서 발생한 예외로 인해 스킵된 항목을 처리합니다.
     * 새로운 트랜잭션(REQUIRES_NEW)에서 외부 정산 상태를 갱신하고 데드 레터를 저장합니다.
     * 
     * @param item 스킵된 외부 정산 데이터 (ExternalSettlement)
     * @param t 발생한 예외 (Throwable)
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onSkipInProcess(ExternalSettlement item, Throwable t) {
        // 발생한 예외 체인을 탐색하여 핵심 예외인 UnmatchableSettlementException을 추출합니다.
        UnmatchableSettlementException targetException = extractTargetException(t);

        if (targetException != null) {
            String errorMessage = targetException.getMessage() != null ? targetException.getMessage() : "UNKNOWN_ERROR";
            // 에러 메시지를 기반으로 실패 사유(FailureReason)를 결정합니다.
            FailureReason reason = determineFailureReason(errorMessage);
            String descriptionSnapshot = item.getDescription() != null ? item.getDescription() : "";

            // 격리 보관할 데드 레터 엔티티를 생성합니다. (원천 데이터의 스냅샷 포함)
            ReconciliationDeadLetter dlq = ReconciliationDeadLetter.isolate(
                    item.getId(),
                    reason,
                    errorMessage,
                    "{\"description_snapshot\": \"" + descriptionSnapshot + "\"}"
            );

            // 외부 정산 엔티티의 최신 상태를 DB에서 다시 읽어와(없으면 전달받은 item 사용) 매칭 실패 상태(UNMATCHED)로 마킹합니다.
            ExternalSettlementId id = new ExternalSettlementId(item.getId(), item.getSettlementDate());
            ExternalSettlement managedItem = settlementRepository.findById(id).orElse(item);

            managedItem.markAsUnmatched();
            settlementRepository.save(managedItem);
            
            // 데드 레터를 저장소에 영속화하고 로그를 남깁니다.
            deadLetterRepository.save(dlq);
            log.warn("[DLQ 격리 완료] 명세 ID: {}, 사유: {}", item.getId(), reason);
        } else {
            log.error("[DLQ 격리 실패] 식별할 수 없는 예외 발생. ID: {}, 원인: {}", item.getId(), t.getMessage());
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