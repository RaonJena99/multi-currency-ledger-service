package com.github.raonjena99.multi_currency_ledger_service.reconciliation.application.service;

import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.github.raonjena99.multi_currency_ledger_service.common.domain.Money;
import com.github.raonjena99.multi_currency_ledger_service.reconciliation.ReconciliationDeadLetterRepository;
import com.github.raonjena99.multi_currency_ledger_service.reconciliation.domain.ExternalSettlement;
import com.github.raonjena99.multi_currency_ledger_service.reconciliation.domain.ReconciliationDeadLetter;
import com.github.raonjena99.multi_currency_ledger_service.reconciliation.domain.event.ReconciliationFeeAdjustedEvent;
import com.github.raonjena99.multi_currency_ledger_service.reconciliation.infrastructure.ExternalSettlementRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class ManualReconciliationService {

    private final ReconciliationDeadLetterRepository deadLetterRepository;
    private final ExternalSettlementRepository settlementRepository;
    private final ApplicationEventPublisher eventPublisher; 

    @Transactional
    public void resolveManually(Long deadLetterId, UUID targetInternalTransactionId, Money feeDifference) {
        // 데드 레터 조회 및 해결 상태(isResolved = true)로 전이
        ReconciliationDeadLetter deadLetter = deadLetterRepository.findById(deadLetterId)
                .orElseThrow(() -> new IllegalArgumentException("DLQ 데이터를 찾을 수 없습니다."));
        deadLetter.markAsResolved();

        // 원천 외부 정산 데이터 단건 조회
        ExternalSettlement settlement = settlementRepository.findByIdWithoutPartitionKey(deadLetter.getExternalSettlementId())
                .orElseThrow(() -> new IllegalStateException("원천 정산 데이터를 찾을 수 없습니다. ID: " + deadLetter.getExternalSettlementId()));
        
        // 상태 전이 로직 호출 (MANUALLY_RESOLVED)
        settlement.resolveManually(targetInternalTransactionId);

        // 수동 차액 보정 분개가 필요한 경우 (차액이 0이 아닐 때)
        if (feeDifference != null && !feeDifference.isZero()) {
            eventPublisher.publishEvent(new ReconciliationFeeAdjustedEvent(settlement.getId(), feeDifference));
            log.info("Manual reconciliation completed by admin and auto-journaling event published. Settlement ID: {}", settlement.getId());
        } else {
            log.info("Manual reconciliation completed by admin (no fee adjustment required). Settlement ID: {}", settlement.getId());
        }
    }
}