package com.github.raonjena99.multi_currency_ledger_service.reconciliation.application.rule;

import lombok.Builder;
import lombok.Getter;

@Getter 
@Builder
public class RuleResult {
    private final boolean passed;
    private final int score;
    private final String failReason;
}
