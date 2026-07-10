package com.github.raonjena99.multi_currency_ledger_service.reconciliation.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import com.github.raonjena99.multi_currency_ledger_service.common.domain.Money;
import com.github.raonjena99.multi_currency_ledger_service.common.model.AssetType;
import com.github.raonjena99.multi_currency_ledger_service.common.model.SettlementStatus;
import com.github.raonjena99.multi_currency_ledger_service.reconciliation.ReconciliationDeadLetterRepository;
import com.github.raonjena99.multi_currency_ledger_service.reconciliation.domain.ExternalSettlement;
import com.github.raonjena99.multi_currency_ledger_service.reconciliation.domain.ReconciliationDeadLetter;
import com.github.raonjena99.multi_currency_ledger_service.reconciliation.domain.event.ReconciliationFeeAdjustedEvent;
import com.github.raonjena99.multi_currency_ledger_service.reconciliation.infrastructure.ExternalSettlementRepository;

@ExtendWith(MockitoExtension.class)
class ManualReconciliationServiceTest {

    @Mock private ReconciliationDeadLetterRepository deadLetterRepository;
    @Mock private ExternalSettlementRepository settlementRepository;
    @Mock private ApplicationEventPublisher eventPublisher;

    @InjectMocks private ManualReconciliationService manualReconciliationService;

    @Test
    @DisplayName("resolveManually - 정상적인 강제 매칭 처리 및 보정 분개 이벤트 발행")
    void testResolveManually() {
        Long deadLetterId = 1L;
        UUID internalTxId = UUID.randomUUID();
        
        ExternalSettlement settlement = ExternalSettlement.create("REF1", "TOSS", OffsetDateTime.now(), "DESC", Money.of("1000", AssetType.FIAT));
        settlement.markAsUnmatched();
        ReconciliationDeadLetter dlq = ReconciliationDeadLetter.isolate(settlement.getId(), com.github.raonjena99.multi_currency_ledger_service.common.model.FailureReason.TEXT_NOT_FOUND, "ERROR", "PAYLOAD");
        
        when(deadLetterRepository.findById(deadLetterId)).thenReturn(Optional.of(dlq));
        when(settlementRepository.findByIdWithoutPartitionKey(settlement.getId())).thenReturn(Optional.of(settlement));

        manualReconciliationService.resolveManually(deadLetterId, internalTxId, Money.of("10", AssetType.FIAT));

        assertThat(dlq.isResolved()).isTrue();
        assertThat(settlement.getStatus()).isEqualTo(SettlementStatus.MANUALLY_RESOLVED);
        assertThat(settlement.getMatchedInternalTransactionId()).isEqualTo(internalTxId);

        verify(eventPublisher).publishEvent(any(ReconciliationFeeAdjustedEvent.class));
    }
    
    @Test
    @DisplayName("resolveManually - 보정 금액이 없을 때 이벤트 미발행")
    void testResolveManually_NoFee() {
        Long deadLetterId = 1L;
        UUID internalTxId = UUID.randomUUID();
        
        ExternalSettlement settlement = ExternalSettlement.create("REF1", "TOSS", OffsetDateTime.now(), "DESC", Money.of("1000", AssetType.FIAT));
        settlement.markAsUnmatched();
        ReconciliationDeadLetter dlq = ReconciliationDeadLetter.isolate(settlement.getId(), com.github.raonjena99.multi_currency_ledger_service.common.model.FailureReason.TEXT_NOT_FOUND, "ERROR", "PAYLOAD");
        
        when(deadLetterRepository.findById(deadLetterId)).thenReturn(Optional.of(dlq));
        when(settlementRepository.findByIdWithoutPartitionKey(settlement.getId())).thenReturn(Optional.of(settlement));

        manualReconciliationService.resolveManually(deadLetterId, internalTxId, Money.of("0", AssetType.FIAT));

        assertThat(dlq.isResolved()).isTrue();
        assertThat(settlement.getStatus()).isEqualTo(SettlementStatus.MANUALLY_RESOLVED);
        
        verify(eventPublisher, never()).publishEvent(any(ReconciliationFeeAdjustedEvent.class));
    }

    @Test
    @DisplayName("resolveManually - 보정 금액이 null일 때 이벤트 미발행")
    void testResolveManually_NullFee() {
        Long deadLetterId = 1L;
        UUID internalTxId = UUID.randomUUID();
        
        ExternalSettlement settlement = ExternalSettlement.create("REF1", "TOSS", OffsetDateTime.now(), "DESC", Money.of("1000", AssetType.FIAT));
        settlement.markAsUnmatched();
        ReconciliationDeadLetter dlq = ReconciliationDeadLetter.isolate(settlement.getId(), com.github.raonjena99.multi_currency_ledger_service.common.model.FailureReason.TEXT_NOT_FOUND, "ERROR", "PAYLOAD");
        
        when(deadLetterRepository.findById(deadLetterId)).thenReturn(Optional.of(dlq));
        when(settlementRepository.findByIdWithoutPartitionKey(settlement.getId())).thenReturn(Optional.of(settlement));

        manualReconciliationService.resolveManually(deadLetterId, internalTxId, null);

        assertThat(dlq.isResolved()).isTrue();
        assertThat(settlement.getStatus()).isEqualTo(SettlementStatus.MANUALLY_RESOLVED);
        
        verify(eventPublisher, never()).publishEvent(any(ReconciliationFeeAdjustedEvent.class));
    }
}
