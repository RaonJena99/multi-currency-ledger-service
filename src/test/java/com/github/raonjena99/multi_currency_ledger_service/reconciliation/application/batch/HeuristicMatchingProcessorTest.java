package com.github.raonjena99.multi_currency_ledger_service.reconciliation.application.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.github.raonjena99.multi_currency_ledger_service.common.domain.Money;
import com.github.raonjena99.multi_currency_ledger_service.common.model.AssetType;
import com.github.raonjena99.multi_currency_ledger_service.common.model.SettlementStatus;
import com.github.raonjena99.multi_currency_ledger_service.reconciliation.application.exception.UnmatchableSettlementException;
import com.github.raonjena99.multi_currency_ledger_service.reconciliation.application.rule.AmountToleranceRule;
import com.github.raonjena99.multi_currency_ledger_service.reconciliation.application.rule.MatchingRule;
import com.github.raonjena99.multi_currency_ledger_service.reconciliation.domain.ExternalSettlement;
import java.math.BigDecimal;
import java.util.ArrayList;

import com.github.raonjena99.multi_currency_ledger_service.reconciliation.application.rule.RuleResult;
import com.github.raonjena99.multi_currency_ledger_service.reconciliation.infrastructure.query.InternalTransactionCandidate;
import com.github.raonjena99.multi_currency_ledger_service.reconciliation.infrastructure.query.InternalTransactionQueryDao;

class HeuristicMatchingProcessorTest {

    @Test
    void process_should_return_matched_result_on_success() {
        InternalTransactionQueryDao queryDao = mock(InternalTransactionQueryDao.class);
        MatchingRule rule = mock(MatchingRule.class);
        when(rule.getOrder()).thenReturn(1);
        
        HeuristicMatchingProcessor processor = new HeuristicMatchingProcessor(queryDao, List.of(rule), "2026-01-01");
        
        ExternalSettlement ext = ExternalSettlement.create("ext1", "PG", OffsetDateTime.now(), "desc", Money.of("100", AssetType.FIAT, "KRW"));
        
        InternalTransactionCandidate candidate = new InternalTransactionCandidate(UUID.randomUUID(), UUID.randomUUID(), OffsetDateTime.now(), "desc", Money.of("100", AssetType.FIAT, "KRW"));
        
        // Return mutable list since process will remove bestMatch
        List<InternalTransactionCandidate> candidatesList = new ArrayList<>();
        candidatesList.add(candidate);
        
        when(queryDao.fetchCandidatesForPeriod(any(), any())).thenReturn(candidatesList);
        
        when(rule.evaluate(any(), any())).thenReturn(RuleResult.builder().passed(true).score(100).build());

        MatchedReconciliationResult result = processor.process(ext);
        
        assertThat(result).isNotNull();
        assertThat(result.matchedTransactionId()).isEqualTo(candidate.transactionId());
    }

    @Test
    void process_should_throw_on_failure() {
        InternalTransactionQueryDao queryDao = mock(InternalTransactionQueryDao.class);
        MatchingRule rule = mock(MatchingRule.class);
        when(rule.getOrder()).thenReturn(1);
        
        HeuristicMatchingProcessor processor = new HeuristicMatchingProcessor(queryDao, List.of(rule), "2026-01-01");
        
        ExternalSettlement ext = ExternalSettlement.create("ext1", "PG", OffsetDateTime.now(), "desc", Money.of("100", AssetType.FIAT, "KRW"));
        
        InternalTransactionCandidate candidate = new InternalTransactionCandidate(UUID.randomUUID(), UUID.randomUUID(), OffsetDateTime.now(), "desc", Money.of("100", AssetType.FIAT, "KRW"));
        
        List<InternalTransactionCandidate> candidatesList = new ArrayList<>();
        candidatesList.add(candidate);
        
        when(queryDao.fetchCandidatesForPeriod(any(), any())).thenReturn(candidatesList);
        
        when(rule.evaluate(any(), any())).thenReturn(RuleResult.builder().passed(false).failReason("RULE_FAILED").build());

        assertThatThrownBy(() -> processor.process(ext))
            .isInstanceOf(UnmatchableSettlementException.class)
            .hasMessageContaining("RULE_FAILED");
    }

    @Test
    void process_should_return_null_if_not_pending() {
        InternalTransactionQueryDao queryDao = mock(InternalTransactionQueryDao.class);
        HeuristicMatchingProcessor processor = new HeuristicMatchingProcessor(queryDao, List.of(), "2026-01-01");
        
        ExternalSettlement ext = ExternalSettlement.create("ext1", "PG", OffsetDateTime.now(), "desc", Money.of("100", AssetType.FIAT, "KRW"));
        ReflectionTestUtils.setField(ext, "status", SettlementStatus.MATCHED);

        assertThat(processor.process(ext)).isNull();
    }

    @Test
    void process_should_remove_eldest_entry_when_cache_exceeds_14() {
        InternalTransactionQueryDao queryDao = mock(InternalTransactionQueryDao.class);
        HeuristicMatchingProcessor processor = new HeuristicMatchingProcessor(queryDao, List.of(), "2026-01-01");
        
        when(queryDao.fetchCandidatesForPeriod(any(), any())).thenReturn(List.of());

        // Cache 15 dates to trigger removeEldestEntry
        for (int i = 0; i < 15; i++) {
            ReflectionTestUtils.invokeMethod(processor, "getCandidatesForDate", LocalDate.now().plusDays(i));
        }

        java.util.Map<LocalDate, List<InternalTransactionCandidate>> cache = 
            (java.util.Map<LocalDate, List<InternalTransactionCandidate>>) ReflectionTestUtils.getField(processor, "dailyCandidatesCache");
        
        assertThat(cache.size()).isLessThanOrEqualTo(14);
    }

    @Test
    void afterChunkError_should_clear_cache() {
        InternalTransactionQueryDao queryDao = mock(InternalTransactionQueryDao.class);
        HeuristicMatchingProcessor processor = new HeuristicMatchingProcessor(queryDao, List.of(), "2026-01-01");
        
        when(queryDao.fetchCandidatesForPeriod(any(), any())).thenReturn(List.of());
        ReflectionTestUtils.invokeMethod(processor, "getCandidatesForDate", LocalDate.now());
        
        java.util.Map<LocalDate, List<InternalTransactionCandidate>> cache = 
            (java.util.Map<LocalDate, List<InternalTransactionCandidate>>) ReflectionTestUtils.getField(processor, "dailyCandidatesCache");
        assertThat(cache).isNotEmpty();

        processor.afterChunkError(null);
        assertThat(cache).isEmpty();
    }
}