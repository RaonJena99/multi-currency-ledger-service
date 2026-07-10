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

import com.github.raonjena99.multi_currency_ledger_service.reconciliation.ReconciliationDeadLetterRepository;
import com.github.raonjena99.multi_currency_ledger_service.reconciliation.application.exception.UnmatchableSettlementException;
import com.github.raonjena99.multi_currency_ledger_service.reconciliation.domain.ExternalSettlement;
import com.github.raonjena99.multi_currency_ledger_service.reconciliation.domain.ReconciliationDeadLetter;
import com.github.raonjena99.multi_currency_ledger_service.reconciliation.infrastructure.ExternalSettlementRepository;

@ExtendWith(MockitoExtension.class)
class ReconciliationSkipListenerTest {

    @Mock private ExternalSettlementRepository settlementRepository;
    @Mock private ReconciliationDeadLetterRepository deadLetterRepository;
    
    @InjectMocks private ReconciliationSkipListener skipListener;

    @Test
    @DisplayName("[SkipListener] 매칭 실패 예외 발생 시 DLQ 엔티티를 영속화하고 상태를 전이한다")
    void onSkipInProcess_ShouldPersistDLQ() {
        ExternalSettlement item = Mockito.mock(ExternalSettlement.class);
        when(item.getId()).thenReturn(UUID.randomUUID());
        when(item.getDescription()).thenReturn("FAILED_DATA");

        UnmatchableSettlementException ex = new UnmatchableSettlementException("AMOUNT_MISMATCH", item.getId().toString());

        skipListener.onSkipInProcess(item, ex);

        verify(item).markAsUnmatched();
        verify(settlementRepository).save(item);
        verify(deadLetterRepository).save(any(ReconciliationDeadLetter.class));
    }

    @Test
    @DisplayName("[SkipListener] 예외가 래핑된 경우 cause를 추적하여 대상 예외를 찾는다")
    void onSkipInProcess_wrappedException() {
        ExternalSettlement item = Mockito.mock(ExternalSettlement.class);
        when(item.getId()).thenReturn(UUID.randomUUID());
        
        Exception targetEx = new UnmatchableSettlementException("TIME_WINDOW_EXCEEDED", item.getId().toString());
        Exception wrapper = new RuntimeException("wrapped", targetEx);

        skipListener.onSkipInProcess(item, wrapper);

        verify(item).markAsUnmatched();
        verify(deadLetterRepository).save(any(ReconciliationDeadLetter.class));
    }

    @Test
    @DisplayName("[SkipListener] 기타 매핑되지 않은 메시지와 null 메시지에 대한 처리")
    void onSkipInProcess_variousMessages() {
        ExternalSettlement item = Mockito.mock(ExternalSettlement.class);
        when(item.getId()).thenReturn(UUID.randomUUID());

        // null message (overridden via anonymous class)
        UnmatchableSettlementException nullMsgEx = new UnmatchableSettlementException(null, item.getId().toString()) {
            @Override
            public String getMessage() {
                return null;
            }
        };
        skipListener.onSkipInProcess(item, nullMsgEx);
        
        // TEXT_NOT_FOUND
        UnmatchableSettlementException textEx = new UnmatchableSettlementException("TEXT_NOT_FOUND", item.getId().toString());
        skipListener.onSkipInProcess(item, textEx);
        
        // unknown message
        UnmatchableSettlementException unknownEx = new UnmatchableSettlementException("RANDOM_ERR", item.getId().toString());
        skipListener.onSkipInProcess(item, unknownEx);

        verify(deadLetterRepository, Mockito.times(3)).save(any(ReconciliationDeadLetter.class));
    }

    @Test
    @DisplayName("[SkipListener] 대상 예외가 아닌 경우 로깅만 하고 종료한다")
    void onSkipInProcess_nonTargetException() {
        ExternalSettlement item = Mockito.mock(ExternalSettlement.class);
        skipListener.onSkipInProcess(item, new IllegalArgumentException("some error"));
        
        verify(settlementRepository, Mockito.never()).save(any());
        verify(deadLetterRepository, Mockito.never()).save(any());
    }
}