package com.github.raonjena99.multi_currency_ledger_service.reconciliation.application.rule;

import org.apache.commons.text.similarity.LevenshteinDistance;
import org.springframework.stereotype.Component;

import com.github.raonjena99.multi_currency_ledger_service.reconciliation.domain.ExternalSettlement;
import com.github.raonjena99.multi_currency_ledger_service.reconciliation.infrastructure.query.InternalTransactionCandidate;

@Component
public class FuzzyTextMatchingRule implements MatchingRule {
    private final LevenshteinDistance levenshtein = LevenshteinDistance.getDefaultInstance();

    @Override public int getOrder() { return 3; }

    @Override
    public RuleResult evaluate(ExternalSettlement external, InternalTransactionCandidate internal) {
        String extDesc = external.getDescription().replaceAll("[^a-zA-Z0-9가-힣]", "").toUpperCase();
        String intDesc = internal.description().replaceAll("[^a-zA-Z0-9가-힣]", "").toUpperCase();
        
        int maxLength = Math.max(extDesc.length(), intDesc.length());
        if (maxLength == 0) return RuleResult.builder().passed(true).score(100).build();

        int score = (int) (((double) (maxLength - levenshtein.apply(extDesc, intDesc)) / maxLength) * 100);
        
        if (score >= 75) return RuleResult.builder().passed(true).score(score).build();
        return RuleResult.builder().passed(false).failReason("TEXT_NOT_FOUND").build();
    }
}
