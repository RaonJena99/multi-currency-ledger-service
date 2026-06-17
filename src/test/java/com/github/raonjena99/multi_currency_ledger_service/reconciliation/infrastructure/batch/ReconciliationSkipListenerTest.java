package com.github.raonjena99.multi_currency_ledger_service.reconciliation.infrastructure.batch;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import com.github.raonjena99.multi_currency_ledger_service.reconciliation.application.exception.UnmatchableSettlementException;
import com.github.raonjena99.multi_currency_ledger_service.reconciliation.domain.ExternalSettlement;
import com.github.raonjena99.multi_currency_ledger_service.reconciliation.domain.ReconciliationDeadLetter;

import jakarta.persistence.EntityManager;

@ExtendWith(MockitoExtension.class)
class ReconciliationSkipListenerTest {

    @Mock private EntityManager entityManager;
    @InjectMocks private ReconciliationSkipListener skipListener;

    @Test
    @DisplayName("[SkipListener] 매칭 실패 예외 발생 시 DLQ 엔티티를 영속화하고 상태를 전이한다")
    void onSkipInProcess_ShouldPersistDLQ() {
        // Given
        ExternalSettlement item = Mockito.mock(ExternalSettlement.class);
        when(item.getId()).thenReturn(UUID.randomUUID());
        when(item.getDescription()).thenReturn("FAILED_DATA");

        UnmatchableSettlementException ex = new UnmatchableSettlementException("AMOUNT_MISMATCH", item.getId().toString());

        // When
        skipListener.onSkipInProcess(item, ex);

        // Then
        verify(item).markAsUnmatched();
        verify(entityManager).merge(item);
        // Persist 검증
        verify(entityManager).persist(any(ReconciliationDeadLetter.class));
    }
}