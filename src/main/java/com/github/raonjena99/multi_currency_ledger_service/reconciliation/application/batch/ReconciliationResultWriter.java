package com.github.raonjena99.multi_currency_ledger_service.reconciliation.application.batch;

import org.springframework.batch.infrastructure.item.Chunk;
import org.springframework.batch.infrastructure.item.ItemWriter;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import com.github.raonjena99.multi_currency_ledger_service.common.domain.Money;
import com.github.raonjena99.multi_currency_ledger_service.reconciliation.domain.ExternalSettlement;
import com.github.raonjena99.multi_currency_ledger_service.reconciliation.domain.event.ReconciliationFeeAdjustedEvent;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class ReconciliationResultWriter implements ItemWriter<MatchedReconciliationResult> {

    private final EntityManager entityManager;
    private final ApplicationEventPublisher eventPublisher;

    @Override
    public void write(Chunk<? extends MatchedReconciliationResult> chunk) {
        for (MatchedReconciliationResult result : chunk) {
            ExternalSettlement settlement = result.externalSettlement();
            Money feeDifference = result.feeDifference();

            entityManager.merge(settlement);

            if (!feeDifference.isZero()) {
                eventPublisher.publishEvent(new ReconciliationFeeAdjustedEvent(settlement.getId(), feeDifference));
                log.debug("Settlement ID: {} - Auto-journaling event for variance {} has been published.", settlement.getId(), feeDifference);
            }
        }
    }
}