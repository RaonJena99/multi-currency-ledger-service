package com.github.raonjena99.multi_currency_ledger_service.reconciliation.infrastructure.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import org.springframework.context.ApplicationEventPublisher;

import com.github.raonjena99.multi_currency_ledger_service.common.domain.Money;
import com.github.raonjena99.multi_currency_ledger_service.common.model.AssetType;
import com.github.raonjena99.multi_currency_ledger_service.common.model.SettlementStatus;
import com.github.raonjena99.multi_currency_ledger_service.reconciliation.application.batch.MatchedReconciliationResult;
import com.github.raonjena99.multi_currency_ledger_service.reconciliation.application.batch.SettlementMatchRecorder;
import com.github.raonjena99.multi_currency_ledger_service.reconciliation.application.batch.SettlementMatchRecorder.MatchOutcome;
import com.github.raonjena99.multi_currency_ledger_service.reconciliation.domain.ExternalSettlement;
import com.github.raonjena99.multi_currency_ledger_service.reconciliation.domain.event.ReconciliationFeeAdjustedEvent;
import com.github.raonjena99.multi_currency_ledger_service.reconciliation.infrastructure.ExternalSettlementRepository;

@ExtendWith(MockitoExtension.class)
@DisplayName("단위 테스트: 대사 결과 라이터")
class ReconciliationResultWriterTest {

    @Mock private ExternalSettlementRepository settlementRepository;
    @Mock private SettlementMatchRecorder settlementMatchRecorder;
    @Mock private ApplicationEventPublisher eventPublisher;

    @InjectMocks private ReconciliationResultWriter writer;

    private ExternalSettlement settlement(String ref) {
        return ExternalSettlement.create(ref, "TOSS", OffsetDateTime.now(), "DESC",
                Money.of("10", AssetType.FIAT, "KRW"));
    }

    private MatchedReconciliationResult result(ExternalSettlement s, String fee) {
        return new MatchedReconciliationResult(s, UUID.randomUUID(), UUID.randomUUID(),
                Money.of(fee, AssetType.FIAT, "KRW"));
    }

    @Test
    @DisplayName("상태에 따라 매칭 여부를 분기하고 차액이 있을 때만 보정 이벤트를 발행한다")
    void write_statuses_and_events() {
        when(settlementMatchRecorder.recordMatch(any())).thenReturn(MatchOutcome.RECORDED);

        ExternalSettlement pending = settlement("REF1");

        ExternalSettlement unmatched = settlement("REF2");
        unmatched.markAsUnmatched();

        ExternalSettlement alreadyMatched = settlement("REF3");
        alreadyMatched.markAsMatched(UUID.randomUUID());

        var r1 = result(pending, "1");                                  // 차액 있음 → 이벤트 발행
        var r2 = new MatchedReconciliationResult(unmatched, UUID.randomUUID(),
                UUID.randomUUID(), Money.zero(AssetType.FIAT, "KRW"));  // 차액 0 → 이벤트 없음
        var r3 = result(alreadyMatched, "1");                           // 이미 MATCHED → 처리 대상 아님

        writer.write(new Chunk<>(List.of(r1, r2, r3)));

        verify(settlementRepository).saveAll(anyList());
        verify(eventPublisher, times(1)).publishEvent(any(ReconciliationFeeAdjustedEvent.class));
        // 이미 MATCHED 인 건은 매칭 기록 자체를 시도하지 않는다.
        verify(settlementMatchRecorder, times(2)).recordMatch(any());

        assertThat(pending.getStatus()).isEqualTo(SettlementStatus.MATCHED);
        assertThat(unmatched.getStatus()).isEqualTo(SettlementStatus.MATCHED);
    }

    @Test
    @DisplayName("다른 정산이 내부 거래를 선점했으면 상태를 바꾸지 않고 건너뛴다")
    void skips_when_internal_transaction_is_taken_by_another_settlement() {
        when(settlementMatchRecorder.recordMatch(any())).thenReturn(MatchOutcome.TAKEN_BY_ANOTHER);

        ExternalSettlement pending = settlement("REF-TAKEN");
        var r = result(pending, "5");

        writer.write(new Chunk<>(List.of(r)));

        assertThat(pending.getStatus())
                .as("선점된 건은 MATCHED 로 전이되면 안 된다")
                .isEqualTo(SettlementStatus.PENDING);
        verify(settlementRepository, never()).saveAll(anyList());
        verify(eventPublisher, never()).publishEvent(any(ReconciliationFeeAdjustedEvent.class));
    }

    @Test
    @DisplayName("이미 기록된 매칭(청크 재실행)은 상태 전이를 이어서 마무리한다")
    void resumes_when_match_was_already_recorded() {
        when(settlementMatchRecorder.recordMatch(any())).thenReturn(MatchOutcome.ALREADY_RECORDED);

        ExternalSettlement pending = settlement("REF-RERUN");
        var r = result(pending, "3");

        writer.write(new Chunk<>(List.of(r)));

        assertThat(pending.getStatus())
                .as("재실행에서 상태 전이를 건너뛰면 정산이 영구히 PENDING 으로 남는다")
                .isEqualTo(SettlementStatus.MATCHED);
        verify(settlementRepository).saveAll(anyList());
        verify(eventPublisher, times(1)).publishEvent(any(ReconciliationFeeAdjustedEvent.class));
    }
}
