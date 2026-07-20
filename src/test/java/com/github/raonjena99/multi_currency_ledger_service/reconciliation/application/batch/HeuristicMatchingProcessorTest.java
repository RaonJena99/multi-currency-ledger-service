package com.github.raonjena99.multi_currency_ledger_service.reconciliation.application.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.github.raonjena99.multi_currency_ledger_service.common.domain.Money;
import com.github.raonjena99.multi_currency_ledger_service.common.model.AssetType;
import com.github.raonjena99.multi_currency_ledger_service.common.model.SettlementStatus;
import com.github.raonjena99.multi_currency_ledger_service.reconciliation.application.exception.UnmatchableSettlementException;
import com.github.raonjena99.multi_currency_ledger_service.reconciliation.application.rule.AmountToleranceRule;
import com.github.raonjena99.multi_currency_ledger_service.reconciliation.application.rule.FuzzyTextMatchingRule;
import com.github.raonjena99.multi_currency_ledger_service.reconciliation.application.rule.MatchingRule;
import com.github.raonjena99.multi_currency_ledger_service.reconciliation.application.rule.TimeToleranceRule;
import com.github.raonjena99.multi_currency_ledger_service.reconciliation.domain.ExternalSettlement;
import com.github.raonjena99.multi_currency_ledger_service.reconciliation.infrastructure.query.InternalTransactionCandidate;
import com.github.raonjena99.multi_currency_ledger_service.reconciliation.infrastructure.query.InternalTransactionQueryDao;

@ExtendWith(MockitoExtension.class)
class HeuristicMatchingProcessorTest {

    @Mock private InternalTransactionQueryDao queryDao; 
    private HeuristicMatchingProcessor processor;

    @BeforeEach
    void setUp() {
        List<MatchingRule> rules = List.of(new TimeToleranceRule(), new AmountToleranceRule(), new FuzzyTextMatchingRule());
        processor = new HeuristicMatchingProcessor(queryDao, rules, "2026-06-01T00:00:00Z");
        
        InternalTransactionCandidate cand = new InternalTransactionCandidate(
                UUID.randomUUID(), OffsetDateTime.parse("2026-06-15T10:00:00Z"), "TOSS PAY", Money.of("1000", AssetType.FIAT, "KRW")
        );
        lenient().when(queryDao.fetchCandidatesForPeriod(any(), any())).thenReturn(new java.util.ArrayList<>(List.of(cand)));
    }

    @Test
    @DisplayName("[Processor] 모든 조건을 만족하면 매칭된 트랜잭션 ID를 DTO에 담아 반환하고, 상태는 변형하지 않는다")
    void process_Success() {
        ExternalSettlement ext = ExternalSettlement.create(
                "REF_SUCCESS", "TOSS", OffsetDateTime.parse("2026-06-15T10:00:00Z"),
                "TOSS PAY", Money.of("1000", AssetType.FIAT, "KRW")
        );

        MatchedReconciliationResult result = processor.process(ext);

        assertThat(result).isNotNull();
        assertThat(result.externalSettlement()).isEqualTo(ext);
        assertThat(result.matchedTransactionId()).isNotNull();
        assertThat(ext.getStatus()).isNotEqualTo(SettlementStatus.MATCHED); 
    }

    @Test
    @DisplayName("[Processor] 금액 오차 범위를 초과하면 예외의 원인이 AMOUNT_MISMATCH로 반환된다")
    void process_Fail_AmountMismatch() {
        ExternalSettlement ext = ExternalSettlement.create(
                "REF_FAIL", "TOSS", OffsetDateTime.parse("2026-06-15T10:00:00Z"),
                "TOSS PAY", Money.of("1200", AssetType.FIAT, "KRW")
        );

        assertThatThrownBy(() -> processor.process(ext))
                .isInstanceOf(UnmatchableSettlementException.class)
                .hasMessage("AMOUNT_MISMATCH");
    }

    @Test
    @DisplayName("[Processor] 여러 개의 후보 중 점수가 가장 높은 것을 선택한다")
    void process_MultipleCandidates_SelectsHighestScore() {
        // First candidate: exact match (score 100 on text)
        InternalTransactionCandidate cand1 = new InternalTransactionCandidate(
                UUID.randomUUID(), OffsetDateTime.parse("2026-06-15T10:00:00Z"), "TOSS PAY", Money.of("1000", AssetType.FIAT, "KRW")
        );
        // Second candidate: partial text match, lower score but still >= 75
        InternalTransactionCandidate cand2 = new InternalTransactionCandidate(
                UUID.randomUUID(), OffsetDateTime.parse("2026-06-15T10:00:00Z"), "TOSS PAY 1", Money.of("1000", AssetType.FIAT, "KRW")
        );
        
        lenient().when(queryDao.fetchCandidatesForPeriod(any(), any())).thenReturn(new java.util.ArrayList<>(List.of(cand1, cand2)));
        // Reset processor to re-initialize cache
        processor = new HeuristicMatchingProcessor(queryDao, List.of(new TimeToleranceRule(), new AmountToleranceRule(), new FuzzyTextMatchingRule()), "2026-06-01T00:00:00Z");

        ExternalSettlement ext = ExternalSettlement.create(
                "REF_SUCCESS", "TOSS", OffsetDateTime.parse("2026-06-15T10:00:00Z"),
                "TOSS PAY", Money.of("1000", AssetType.FIAT, "KRW")
        );

        MatchedReconciliationResult result = processor.process(ext);
        assertThat(result.matchedTransactionId()).isEqualTo(cand1.transactionId());
    }
}