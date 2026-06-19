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

@Slf4j
@Component
@RequiredArgsConstructor
public class ReconciliationResultWriter implements ItemWriter<MatchedReconciliationResult> {

    private final ExternalSettlementRepository settlementRepository;

    @Override
    public void write(Chunk<? extends MatchedReconciliationResult> chunk) throws Exception {
        List<ExternalSettlement> settlementsToUpdate = chunk.getItems().stream()
            .map(result -> {
                ExternalSettlement external = result.externalSettlement();
                
                if (external.getStatus() == SettlementStatus.PENDING || external.getStatus() == SettlementStatus.UNMATCHED) {
                    external.markAsMatched(result.matchedTransactionId());
                }
                                
                return external;
            })
            .collect(Collectors.toList());

        settlementRepository.saveAll(settlementsToUpdate);
        
        log.info("Successfully wrote and matched {} settlements.", chunk.size());
    }
}