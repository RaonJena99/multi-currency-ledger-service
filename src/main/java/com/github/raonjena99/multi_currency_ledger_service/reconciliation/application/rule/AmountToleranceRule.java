package com.github.raonjena99.multi_currency_ledger_service.reconciliation.application.rule;

import java.math.BigDecimal;

import org.springframework.stereotype.Component;

import com.github.raonjena99.multi_currency_ledger_service.reconciliation.domain.ExternalSettlement;
import com.github.raonjena99.multi_currency_ledger_service.reconciliation.infrastructure.query.InternalTransactionCandidate;

@Component
public class AmountToleranceRule implements MatchingRule {
    private static final BigDecimal TOLERANCE = new BigDecimal("100");

    @Override public int getOrder() { return 2; }

    @Override
    public RuleResult evaluate(ExternalSettlement external, InternalTransactionCandidate internal) {
        BigDecimal diff = external.getAmount().subtract(internal.amount()).getAmount().abs();
        if (diff.compareTo(TOLERANCE) <= 0) return RuleResult.builder().passed(true).score(100).build();
        return RuleResult.builder().passed(false).failReason("AMOUNT_MISMATCH").build();
    }
}
