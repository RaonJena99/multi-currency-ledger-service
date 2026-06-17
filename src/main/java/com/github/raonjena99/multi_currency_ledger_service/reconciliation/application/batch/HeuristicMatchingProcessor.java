package com.github.raonjena99.multi_currency_ledger_service.reconciliation.application.batch;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.listener.StepExecutionListener;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.infrastructure.item.ItemProcessor;
import org.springframework.stereotype.Component;

import com.github.raonjena99.multi_currency_ledger_service.common.domain.Money;
import com.github.raonjena99.multi_currency_ledger_service.reconciliation.application.exception.UnmatchableSettlementException;
import com.github.raonjena99.multi_currency_ledger_service.reconciliation.application.rule.MatchingRule;
import com.github.raonjena99.multi_currency_ledger_service.reconciliation.application.rule.RuleResult;
import com.github.raonjena99.multi_currency_ledger_service.reconciliation.domain.ExternalSettlement;
import com.github.raonjena99.multi_currency_ledger_service.reconciliation.infrastructure.query.InternalTransactionCandidate;
import com.github.raonjena99.multi_currency_ledger_service.reconciliation.infrastructure.query.InternalTransactionQueryDao;

import lombok.RequiredArgsConstructor;

@StepScope
@Component
@RequiredArgsConstructor
public class HeuristicMatchingProcessor implements ItemProcessor<ExternalSettlement, MatchedReconciliationResult>, StepExecutionListener { // [Fix 2] 명시적 인터페이스 구현

    private final InternalTransactionQueryDao queryDao;
    private final List<MatchingRule> rules;

    private Map<LocalDate, List<InternalTransactionCandidate>> monthlyCandidatesCache;

    @Override
    public void beforeStep(StepExecution stepExecution) {
        OffsetDateTime startOfMonth = OffsetDateTime.parse(stepExecution.getJobParameters().getString("startOfMonth"));
        OffsetDateTime endOfMonth = startOfMonth.plusMonths(1);

        List<InternalTransactionCandidate> rawCandidates = queryDao.fetchCandidatesForPeriod(startOfMonth, endOfMonth);
        
        this.monthlyCandidatesCache = rawCandidates.stream()
            .collect(Collectors.groupingBy(c -> c.transactedAt().toLocalDate()));
    }

    @Override
    public MatchedReconciliationResult process(ExternalSettlement external) {
        LocalDate targetDate = external.getSettlementDate().toLocalDate();
        List<InternalTransactionCandidate> searchSpace = new ArrayList<>();

        for (int i = -3; i <= 3; i++) {
            searchSpace.addAll(monthlyCandidatesCache.getOrDefault(targetDate.plusDays(i), Collections.emptyList()));
        }

        InternalTransactionCandidate bestMatch = null;
        int highestScore = -1;
        String lastFailReason = "TIME_WINDOW_EXCEEDED";

        for (InternalTransactionCandidate candidate : searchSpace) {
            boolean allPassed = true;
            int totalScore = 0;

            for (MatchingRule rule : rules) {
                RuleResult result = rule.evaluate(external, candidate);
                if (!result.isPassed()) {
                    lastFailReason = result.getFailReason();
                    allPassed = false;
                    break;
                }
                totalScore += result.getScore();
            }

            if (allPassed && totalScore > highestScore) {
                highestScore = totalScore;
                bestMatch = candidate;
            }
        }

        if (bestMatch != null) {
            external.markAsMatched(bestMatch.transactionId());
            Money feeDifference = external.getAmount().subtract(bestMatch.amount());
            return new MatchedReconciliationResult(external, feeDifference);
        }

        throw new UnmatchableSettlementException(lastFailReason, external.getId().toString());
    }
}