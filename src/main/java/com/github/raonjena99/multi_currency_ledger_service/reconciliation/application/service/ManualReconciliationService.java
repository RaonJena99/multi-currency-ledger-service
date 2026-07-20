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

/**
 * 관리자(Admin)가 자동 매칭에 실패하여 데드 레터(DLQ)로 빠진 건들을 수동으로 대사 처리할 수 있게 하는 서비스(Service) 클래스입니다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ManualReconciliationService {

    private final ReconciliationDeadLetterRepository deadLetterRepository;
    private final ExternalSettlementRepository settlementRepository;
    private final ApplicationEventPublisher eventPublisher; 
    private final com.github.raonjena99.multi_currency_ledger_service.reconciliation.infrastructure.query.InternalTransactionQueryDao internalTransactionQueryDao;

    /**
     * 데드 레터 큐(DLQ)에 있는 특정 실패 건을 특정 내부 거래 ID와 강제로 매칭시켜 수동 해결(Resolve) 처리합니다.
     * 차액(feeDifference)이 발생할 경우 차액 보정 분개 이벤트를 발행합니다.
     * 
     * @param deadLetterId 수동 처리할 데드 레터 ID
     * @param targetInternalTransactionId 매칭 대상이 되는 내부 거래 ID (UUID)
     * @param feeDifference 수동으로 보정할 수수료 차액 (Money)
     */
    @Transactional
    public void resolveManually(Long deadLetterId, UUID targetInternalTransactionId, Money feeDifference) {
        // 데드 레터 조회 및 해결 상태(isResolved = true)로 전이
        ReconciliationDeadLetter deadLetter = deadLetterRepository.findById(deadLetterId)
                .orElseThrow(() -> new IllegalArgumentException("DLQ 데이터를 찾을 수 없습니다."));
        deadLetter.markAsResolved();

        // 원천 외부 정산 데이터 단건 조회
        ExternalSettlement settlement = settlementRepository.findByIdWithoutPartitionKey(deadLetter.getExternalSettlementId())
                .orElseThrow(() -> new IllegalStateException("원천 정산 데이터를 찾을 수 없습니다. ID: " + deadLetter.getExternalSettlementId()));
        
        // 수동 매핑 1:1 제약조건 확인
        if (settlementRepository.existsByMatchedInternalTransactionId(targetInternalTransactionId)) {
            throw new IllegalStateException("해당 내부 거래는 이미 다른 외부 정산과 매칭되었습니다.");
        }

        // 상태 전이 로직 호출 (MANUALLY_RESOLVED)
        settlement.resolveManually(targetInternalTransactionId);

        // 실제 Account ID 조회
        UUID realAccountId = internalTransactionQueryDao.findAccountIdByTransactionId(targetInternalTransactionId);

        // 수동 차액 보정 분개가 필요한 경우 (차액이 0이 아닐 때)
        if (feeDifference != null && !feeDifference.isZero()) {
            eventPublisher.publishEvent(ReconciliationFeeAdjustedEvent.of(settlement.getId(), targetInternalTransactionId, realAccountId, feeDifference));
            log.info("Manual reconciliation completed by admin and auto-journaling event published. Settlement ID: {}", settlement.getId());
        } else {
            log.info("Manual reconciliation completed by admin (no fee adjustment required). Settlement ID: {}", settlement.getId());
        }
    }
}