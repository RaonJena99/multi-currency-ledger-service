package com.github.raonjena99.multi_currency_ledger_service.reconciliation.infrastructure.batch;

import java.util.ArrayList;
import java.util.List;

import org.springframework.batch.infrastructure.item.Chunk;
import org.springframework.batch.infrastructure.item.ItemWriter;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import com.github.raonjena99.multi_currency_ledger_service.common.model.SettlementStatus;
import com.github.raonjena99.multi_currency_ledger_service.reconciliation.application.batch.MatchedReconciliationResult;
import com.github.raonjena99.multi_currency_ledger_service.reconciliation.application.batch.SettlementMatchRecorder;
import com.github.raonjena99.multi_currency_ledger_service.reconciliation.application.batch.SettlementMatchRecorder.MatchOutcome;
import com.github.raonjena99.multi_currency_ledger_service.reconciliation.domain.ExternalSettlement;
import com.github.raonjena99.multi_currency_ledger_service.reconciliation.domain.SettlementMatch;
import com.github.raonjena99.multi_currency_ledger_service.reconciliation.domain.event.ReconciliationFeeAdjustedEvent;
import com.github.raonjena99.multi_currency_ledger_service.reconciliation.infrastructure.ExternalSettlementRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 매칭에 성공한 대사 결과를 DB 에 반영하고 상태를 갱신하는 ItemWriter 입니다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReconciliationResultWriter implements ItemWriter<MatchedReconciliationResult> {

    private final ExternalSettlementRepository settlementRepository;
    private final SettlementMatchRecorder settlementMatchRecorder;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * Chunk 단위로 전달된 대사 성공 결과를 반영합니다.
     * 차액(feeDifference)이 발생한 경우 차액 보정 분개 이벤트를 발행합니다.
     *
     * @param chunk 저장할 대사 매칭 결과 묶음
     */
    @Override
    public void write(Chunk<? extends MatchedReconciliationResult> chunk) {
        List<ExternalSettlement> settlementsToUpdate = new ArrayList<>();

        for (MatchedReconciliationResult result : chunk.getItems()) {
            ExternalSettlement external = result.externalSettlement();

            if (external.getStatus() != SettlementStatus.PENDING
                    && external.getStatus() != SettlementStatus.UNMATCHED) {
                continue;
            }

            // 1:1 매칭을 DB 제약으로 확정한다. 애플리케이션 검사만으로는 동시 실행을 막을 수 없다.
            // 독립 트랜잭션에서 기록되므로 제약조건 위반이 이 청크를 오염시키지 않는다.
            var outcome = settlementMatchRecorder.recordMatch(SettlementMatch.of(
                    result.matchedTransactionId(), external.getId(), external.getSettlementDate()));

            if (!outcome.isMatchable()) {
                log.warn("내부 거래 {} 는 이미 다른 정산과 매칭되어 있습니다. 이 건은 미매칭으로 남깁니다. settlementId={}",
                        result.matchedTransactionId(), external.getId());
                continue;
            }

            if (outcome == MatchOutcome.ALREADY_RECORDED) {
                // 청크 롤백 후 건별 재실행 경로다. 매칭 행은 이미 커밋되어 있으므로
                // 정산 상태 전이만 이어서 마무리한다.
                log.info("이미 기록된 매칭을 이어서 확정합니다(재실행). internalTransactionId={}, settlementId={}",
                        result.matchedTransactionId(), external.getId());
            }

            external.markAsMatched(result.matchedTransactionId());
            settlementsToUpdate.add(external);

            if (result.feeDifference() != null && !result.feeDifference().isZero()) {
                eventPublisher.publishEvent(
                    ReconciliationFeeAdjustedEvent.of(
                        external.getId(),
                        result.matchedTransactionId(),
                        result.accountId(),
                        result.feeDifference()
                    )
                );
            }
        }

        if (!settlementsToUpdate.isEmpty()) {
            settlementRepository.saveAll(settlementsToUpdate);
        }

        log.info("대사 결과 반영 완료. 입력 {}건 중 {}건 매칭.", chunk.size(), settlementsToUpdate.size());
    }
}
