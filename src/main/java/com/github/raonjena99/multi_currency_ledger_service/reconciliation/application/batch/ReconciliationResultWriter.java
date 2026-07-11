package com.github.raonjena99.multi_currency_ledger_service.reconciliation.application.batch;

import java.util.List;
import java.util.stream.Collectors;

import org.springframework.batch.infrastructure.item.Chunk;
import org.springframework.batch.infrastructure.item.ItemWriter;
import org.springframework.stereotype.Component;

import com.github.raonjena99.multi_currency_ledger_service.common.model.SettlementStatus;
import com.github.raonjena99.multi_currency_ledger_service.reconciliation.domain.ExternalSettlement;
import com.github.raonjena99.multi_currency_ledger_service.reconciliation.infrastructure.ExternalSettlementRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Spring Batch의 ItemWriter를 구현하여, 매칭에 성공한 MatchedReconciliationResult(대사 결과)를
 * 데이터베이스(DB)에 저장하고 ExternalSettlement(외부 정산)의 상태를 업데이트하는 클래스입니다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReconciliationResultWriter implements ItemWriter<MatchedReconciliationResult> {

    private final ExternalSettlementRepository settlementRepository;

    /**
     * Chunk 단위로 전달된 대사 성공 결과들을 받아, 외부 정산 데이터의 상태를 MATCHED로 변경하고 일괄 저장(saveAll)합니다.
     * 
     * @param chunk 저장할 대사 매칭 결과 묶음 (Chunk<? extends MatchedReconciliationResult>)
     * @throws Exception 저장 중 예외 발생 시
     */
    @Override
    public void write(Chunk<? extends MatchedReconciliationResult> chunk) throws Exception {
        // 매칭된 결과 청크(chunk)를 순회하며 상태를 업데이트할 ExternalSettlement 목록을 생성합니다.
        List<ExternalSettlement> settlementsToUpdate = chunk.getItems().stream()
            .map(result -> {
                ExternalSettlement external = result.externalSettlement();
                
                // 기존 상태가 대기 중(PENDING)이거나 매칭 실패(UNMATCHED)인 경우에만 성공 상태로 갱신합니다.
                if (external.getStatus() == SettlementStatus.PENDING || external.getStatus() == SettlementStatus.UNMATCHED) {
                    external.markAsMatched(result.matchedTransactionId());
                }
                                
                return external;
            })
            .collect(Collectors.toList());

        // 상태가 변경된 엔티티들을 데이터베이스에 일괄 반영합니다.
        settlementRepository.saveAll(settlementsToUpdate);
        
        log.info("Successfully wrote and matched {} settlements.", chunk.size());
    }
}