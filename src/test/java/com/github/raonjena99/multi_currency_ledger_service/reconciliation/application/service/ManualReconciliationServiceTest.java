package com.github.raonjena99.multi_currency_ledger_service.reconciliation.application.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import com.github.raonjena99.multi_currency_ledger_service.common.domain.Money;
import com.github.raonjena99.multi_currency_ledger_service.common.model.AssetType;
import com.github.raonjena99.multi_currency_ledger_service.reconciliation.ReconciliationDeadLetterRepository;
import com.github.raonjena99.multi_currency_ledger_service.reconciliation.domain.ExternalSettlement;
import com.github.raonjena99.multi_currency_ledger_service.reconciliation.domain.ReconciliationDeadLetter;
import com.github.raonjena99.multi_currency_ledger_service.reconciliation.infrastructure.ExternalSettlementRepository;
import com.github.raonjena99.multi_currency_ledger_service.reconciliation.infrastructure.SettlementMatchRepository;
import com.github.raonjena99.multi_currency_ledger_service.reconciliation.infrastructure.query.InternalTransactionQueryDao;

class ManualReconciliationServiceTest {

    @Test
    void resolveManually_should_publish_event_when_fee_is_non_zero() {
        ReconciliationDeadLetterRepository dlqRepo = mock(ReconciliationDeadLetterRepository.class);
        ExternalSettlementRepository settlementRepo = mock(ExternalSettlementRepository.class);
        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
        InternalTransactionQueryDao queryDao = mock(InternalTransactionQueryDao.class);
        
        SettlementMatchRepository matchRepo = mock(SettlementMatchRepository.class);
        ManualReconciliationService service = new ManualReconciliationService(dlqRepo, settlementRepo, matchRepo, eventPublisher, queryDao);
        
        ReconciliationDeadLetter dlq = mock(ReconciliationDeadLetter.class);
        when(dlqRepo.findById(1L)).thenReturn(Optional.of(dlq));
        UUID extId = UUID.randomUUID();
        when(dlq.getExternalSettlementId()).thenReturn(extId);
        
        ExternalSettlement settlement = mock(ExternalSettlement.class);
        when(settlement.getId()).thenReturn(UUID.randomUUID());
        when(settlementRepo.findByIdWithoutPartitionKey(extId)).thenReturn(Optional.of(settlement));
        
        UUID tId = UUID.randomUUID();
        
        when(queryDao.findAccountIdByTransactionId(tId)).thenReturn(UUID.randomUUID());
        
        service.resolveManually(1L, tId, Money.of("10", AssetType.FIAT, "KRW"));
        
        verify(dlq).markAsResolved();
        verify(settlement).resolveManually(tId);
        verify(eventPublisher).publishEvent(any(com.github.raonjena99.multi_currency_ledger_service.reconciliation.domain.event.ReconciliationFeeAdjustedEvent.class));
    }

    @Test
    void resolveManually_should_not_publish_event_when_fee_is_zero() {
        ReconciliationDeadLetterRepository dlqRepo = mock(ReconciliationDeadLetterRepository.class);
        ExternalSettlementRepository settlementRepo = mock(ExternalSettlementRepository.class);
        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
        InternalTransactionQueryDao queryDao = mock(InternalTransactionQueryDao.class);
        
        SettlementMatchRepository matchRepo = mock(SettlementMatchRepository.class);
        ManualReconciliationService service = new ManualReconciliationService(dlqRepo, settlementRepo, matchRepo, eventPublisher, queryDao);
        
        ReconciliationDeadLetter dlq = mock(ReconciliationDeadLetter.class);
        when(dlqRepo.findById(1L)).thenReturn(Optional.of(dlq));
        UUID extId = UUID.randomUUID();
        when(dlq.getExternalSettlementId()).thenReturn(extId);
        
        ExternalSettlement settlement = mock(ExternalSettlement.class);
        when(settlementRepo.findByIdWithoutPartitionKey(extId)).thenReturn(Optional.of(settlement));
        
        UUID tId = UUID.randomUUID();
        
        when(queryDao.findAccountIdByTransactionId(tId)).thenReturn(UUID.randomUUID());
        
        service.resolveManually(1L, tId, Money.zero(AssetType.FIAT, "KRW"));
        
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void resolveManually_should_throw_if_dlq_not_found() {
        ReconciliationDeadLetterRepository dlqRepo = mock(ReconciliationDeadLetterRepository.class);
        ExternalSettlementRepository settlementRepo = mock(ExternalSettlementRepository.class);
        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
        InternalTransactionQueryDao queryDao = mock(InternalTransactionQueryDao.class);
        
        SettlementMatchRepository matchRepo = mock(SettlementMatchRepository.class);
        ManualReconciliationService service = new ManualReconciliationService(dlqRepo, settlementRepo, matchRepo, eventPublisher, queryDao);
        
        when(dlqRepo.findById(1L)).thenReturn(Optional.empty());
        
        assertThatThrownBy(() -> service.resolveManually(1L, UUID.randomUUID(), Money.zero(AssetType.FIAT, "KRW")))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void resolveManually_should_throw_if_settlement_not_found() {
        ReconciliationDeadLetterRepository dlqRepo = mock(ReconciliationDeadLetterRepository.class);
        ExternalSettlementRepository settlementRepo = mock(ExternalSettlementRepository.class);
        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
        InternalTransactionQueryDao queryDao = mock(InternalTransactionQueryDao.class);
        
        SettlementMatchRepository matchRepo = mock(SettlementMatchRepository.class);
        ManualReconciliationService service = new ManualReconciliationService(dlqRepo, settlementRepo, matchRepo, eventPublisher, queryDao);
        
        ReconciliationDeadLetter dlq = mock(ReconciliationDeadLetter.class);
        when(dlqRepo.findById(1L)).thenReturn(Optional.of(dlq));
        UUID extId = UUID.randomUUID();
        when(dlq.getExternalSettlementId()).thenReturn(extId);
        
        when(settlementRepo.findByIdWithoutPartitionKey(extId)).thenReturn(Optional.empty());
        
        assertThatThrownBy(() -> service.resolveManually(1L, UUID.randomUUID(), Money.zero(AssetType.FIAT, "KRW")))
            .isInstanceOf(IllegalStateException.class);
    }
    
    @Test
    void resolveManually_should_throw_if_already_matched() {
        ReconciliationDeadLetterRepository dlqRepo = mock(ReconciliationDeadLetterRepository.class);
        ExternalSettlementRepository settlementRepo = mock(ExternalSettlementRepository.class);
        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
        InternalTransactionQueryDao queryDao = mock(InternalTransactionQueryDao.class);
        
        SettlementMatchRepository matchRepo = mock(SettlementMatchRepository.class);
        ManualReconciliationService service = new ManualReconciliationService(dlqRepo, settlementRepo, matchRepo, eventPublisher, queryDao);
        
        ReconciliationDeadLetter dlq = mock(ReconciliationDeadLetter.class);
        when(dlqRepo.findById(1L)).thenReturn(Optional.of(dlq));
        UUID extId = UUID.randomUUID();
        when(dlq.getExternalSettlementId()).thenReturn(extId);
        
        ExternalSettlement settlement = mock(ExternalSettlement.class);
        when(settlementRepo.findByIdWithoutPartitionKey(extId)).thenReturn(Optional.of(settlement));
        
        UUID tId = UUID.randomUUID();
        org.mockito.Mockito.when(matchRepo.saveAndFlush(org.mockito.ArgumentMatchers.any()))
            .thenThrow(new org.springframework.dao.DataIntegrityViolationException("duplicate match"));
        
        assertThatThrownBy(() -> service.resolveManually(1L, tId, Money.zero(AssetType.FIAT, "KRW")))
            .isInstanceOf(IllegalStateException.class);
    }
}
