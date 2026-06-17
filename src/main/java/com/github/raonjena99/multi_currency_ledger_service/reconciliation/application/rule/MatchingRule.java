package com.github.raonjena99.multi_currency_ledger_service.reconciliation.application.rule;

import com.github.raonjena99.multi_currency_ledger_service.reconciliation.domain.ExternalSettlement;
import com.github.raonjena99.multi_currency_ledger_service.reconciliation.infrastructure.query.InternalTransactionCandidate;

public interface MatchingRule {
    RuleResult evaluate(ExternalSettlement external, InternalTransactionCandidate internal);
    int getOrder(); 
}
