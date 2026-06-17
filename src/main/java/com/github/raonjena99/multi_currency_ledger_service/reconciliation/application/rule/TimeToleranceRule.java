package com.github.raonjena99.multi_currency_ledger_service.reconciliation.application.rule;

import java.time.temporal.ChronoUnit;

import org.springframework.stereotype.Component;

import com.github.raonjena99.multi_currency_ledger_service.reconciliation.domain.ExternalSettlement;
import com.github.raonjena99.multi_currency_ledger_service.reconciliation.infrastructure.query.InternalTransactionCandidate;

@Component
public class TimeToleranceRule implements MatchingRule {
    @Override public int getOrder() { return 1; } 

    @Override
    public RuleResult evaluate(ExternalSettlement external, InternalTransactionCandidate internal) {
        long diffDays = Math.abs(ChronoUnit.DAYS.between(
            external.getSettlementDate().toLocalDate(), 
            internal.transactedAt().toLocalDate()
        ));
        
        if (diffDays <= 3) return RuleResult.builder().passed(true).score(100).build();
        return RuleResult.builder().passed(false).failReason("TIME_WINDOW_EXCEEDED").build();
    }
}
