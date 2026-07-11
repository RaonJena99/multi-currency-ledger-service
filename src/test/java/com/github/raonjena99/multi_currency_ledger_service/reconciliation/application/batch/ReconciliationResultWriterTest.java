package com.github.raonjena99.multi_currency_ledger_service.reconciliation.application.batch;

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

    @Test
    @DisplayName("write - 상태에 따른 markAsMatched 로직 분기 테스트")
    void write_statuses() throws Exception {
        ExternalSettlement pending = ExternalSettlement.create("REF1", "TOSS", OffsetDateTime.now(), "DESC", Money.of("10", AssetType.FIAT));
        
        ExternalSettlement unmatched = ExternalSettlement.create("REF2", "TOSS", OffsetDateTime.now(), "DESC", Money.of("10", AssetType.FIAT));
        unmatched.markAsUnmatched();
        
        ExternalSettlement matched = ExternalSettlement.create("REF3", "TOSS", OffsetDateTime.now(), "DESC", Money.of("10", AssetType.FIAT));
        matched.markAsMatched(UUID.randomUUID());

        MatchedReconciliationResult r1 = new MatchedReconciliationResult(pending, UUID.randomUUID(), Money.zero(AssetType.FIAT));
        MatchedReconciliationResult r2 = new MatchedReconciliationResult(unmatched, UUID.randomUUID(), Money.zero(AssetType.FIAT));
        MatchedReconciliationResult r3 = new MatchedReconciliationResult(matched, UUID.randomUUID(), Money.zero(AssetType.FIAT));

        Chunk<MatchedReconciliationResult> chunk = new Chunk<>(List.of(r1, r2, r3));

        writer.write(chunk);

        verify(settlementRepository).saveAll(anyList());
    }
}
