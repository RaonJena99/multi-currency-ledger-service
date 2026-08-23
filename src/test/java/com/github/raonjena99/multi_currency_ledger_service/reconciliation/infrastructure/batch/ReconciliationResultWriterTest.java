package com.github.raonjena99.multi_currency_ledger_service.reconciliation.infrastructure.batch;

import com.github.raonjena99.multi_currency_ledger_service.reconciliation.application.batch.MatchedReconciliationResult;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.verify;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.batch.infrastructure.item.Chunk;

import com.github.raonjena99.multi_currency_ledger_service.common.domain.Money;
import com.github.raonjena99.multi_currency_ledger_service.common.model.AssetType;
import com.github.raonjena99.multi_currency_ledger_service.reconciliation.domain.ExternalSettlement;
import com.github.raonjena99.multi_currency_ledger_service.reconciliation.infrastructure.ExternalSettlementRepository;

@ExtendWith(MockitoExtension.class)
class ReconciliationResultWriterTest {

    @Mock
    private ExternalSettlementRepository settlementRepository;

    @InjectMocks
    private ReconciliationResultWriter writer;

    @Mock
    private org.springframework.context.ApplicationEventPublisher eventPublisher;

    @Test
    @DisplayName("write - 상태에 따른 markAsMatched 로직 분기 및 이벤트 발행 테스트")
    void write_statuses_and_events() throws Exception {
        ExternalSettlement pending = ExternalSettlement.create("REF1", "TOSS", OffsetDateTime.now(), "DESC", Money.of("10", AssetType.FIAT, "KRW"));
        
        ExternalSettlement unmatched = ExternalSettlement.create("REF2", "TOSS", OffsetDateTime.now(), "DESC", Money.of("10", AssetType.FIAT, "KRW"));
        unmatched.markAsUnmatched();
        
        ExternalSettlement matched = ExternalSettlement.create("REF3", "TOSS", OffsetDateTime.now(), "DESC", Money.of("10", AssetType.FIAT, "KRW"));
        matched.markAsMatched(UUID.randomUUID());

        // pending with non-zero fee -> should publish event
        MatchedReconciliationResult r1 = new MatchedReconciliationResult(pending, UUID.randomUUID(), UUID.randomUUID(), Money.of("1", AssetType.FIAT, "KRW"));
        // unmatched with zero fee -> shouldn't publish event
        MatchedReconciliationResult r2 = new MatchedReconciliationResult(unmatched, UUID.randomUUID(), UUID.randomUUID(), Money.zero(AssetType.FIAT, "KRW"));
        // matched with non-zero fee -> shouldn't process because already MATCHED
        MatchedReconciliationResult r3 = new MatchedReconciliationResult(matched, UUID.randomUUID(), UUID.randomUUID(), Money.of("1", AssetType.FIAT, "KRW"));

        Chunk<MatchedReconciliationResult> chunk = new Chunk<>(List.of(r1, r2, r3));

        writer.write(chunk);

        verify(settlementRepository).saveAll(anyList());
        verify(eventPublisher, org.mockito.Mockito.times(1)).publishEvent(org.mockito.ArgumentMatchers.any(com.github.raonjena99.multi_currency_ledger_service.reconciliation.domain.event.ReconciliationFeeAdjustedEvent.class));
    }
}
