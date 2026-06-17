package com.github.raonjena99.multi_currency_ledger_service.reconciliation.application.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.test.MetaDataInstanceFactory;

import com.github.raonjena99.multi_currency_ledger_service.common.domain.Money;
import com.github.raonjena99.multi_currency_ledger_service.common.model.AssetType;
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
        // 실제 룰 엔진들을 파이프라인에 주입
        List<MatchingRule> rules = List.of(new TimeToleranceRule(), new AmountToleranceRule(), new FuzzyTextMatchingRule());
        processor = new HeuristicMatchingProcessor(queryDao, rules);
        
        // BeforeStep 생명주기 모의 실행
        StepExecution stepExecution = MetaDataInstanceFactory.createStepExecution(
                new JobParametersBuilder().addString("startOfMonth", "2026-06-01T00:00:00Z").toJobParameters()
        );
        
        InternalTransactionCandidate cand = new InternalTransactionCandidate(
                UUID.randomUUID(), OffsetDateTime.parse("2026-06-15T10:00:00Z"), "TOSS_PAY", Money.of("1000", AssetType.FIAT)
        );
        when(queryDao.fetchCandidatesForPeriod(any(), any())).thenReturn(List.of(cand));
        
        processor.beforeStep(stepExecution);
    }

    @Test
    @DisplayName("[Processor] 모든 조건을 만족하면 매칭 상태로 마킹하고 반환한다")
    void process_Success() {
        // Given
        ExternalSettlement ext = mock(ExternalSettlement.class);
        when(ext.getSettlementDate()).thenReturn(OffsetDateTime.parse("2026-06-15T15:00:00Z"));
        when(ext.getAmount()).thenReturn(Money.of("1000", AssetType.FIAT));
        when(ext.getDescription()).thenReturn("TOSS PAY");

        // When
        MatchedReconciliationResult result = processor.process(ext);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.externalSettlement()).isEqualTo(ext);
        verify(ext, times(1)).markAsMatched(any(UUID.class));
    }

    @Test
    @DisplayName("[Processor] 금액 오차 범위를 초과하면 예외의 원인이 AMOUNT_MISMATCH로 반환된다")
    void process_Fail_AmountMismatch() {
        // Given
        ExternalSettlement ext = mock(ExternalSettlement.class);
        when(ext.getId()).thenReturn(UUID.randomUUID());
        when(ext.getSettlementDate()).thenReturn(OffsetDateTime.parse("2026-06-15T15:00:00Z"));
        when(ext.getAmount()).thenReturn(Money.of("1200", AssetType.FIAT));

        // When & Then
        assertThatThrownBy(() -> processor.process(ext))
                .isInstanceOf(UnmatchableSettlementException.class)
                .hasMessage("AMOUNT_MISMATCH");
    }
}