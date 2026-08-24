package com.github.raonjena99.multi_currency_ledger_service.reconciliation.application.rule;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;

import org.junit.jupiter.api.Test;

import com.github.raonjena99.multi_currency_ledger_service.common.domain.Money;
import com.github.raonjena99.multi_currency_ledger_service.common.model.AssetType;
import com.github.raonjena99.multi_currency_ledger_service.reconciliation.domain.ExternalSettlement;
import com.github.raonjena99.multi_currency_ledger_service.reconciliation.infrastructure.query.InternalTransactionCandidate;

class MatchingRuleMiscTest {

    @Test
    void getOrder_returnsCorrectValues() {
        assertThat(new TimeToleranceRule().getOrder()).isEqualTo(1);
        assertThat(new AmountToleranceRule(new java.math.BigDecimal("0.005"), new java.math.BigDecimal("100")).getOrder()).isEqualTo(2);
        assertThat(new FuzzyTextMatchingRule().getOrder()).isEqualTo(3);
    }

    @Test
    void fuzzyTextMatchingRule_emptyStrings() {
        FuzzyTextMatchingRule rule = new FuzzyTextMatchingRule();
        
        ExternalSettlement external = ExternalSettlement.create("REF1", "TOSS", OffsetDateTime.now(), "!@#", Money.of("1000", AssetType.FIAT, "KRW"));
        InternalTransactionCandidate internal = new InternalTransactionCandidate(java.util.UUID.randomUUID(), java.util.UUID.randomUUID(), OffsetDateTime.now(), "$%^", Money.of("1000", AssetType.FIAT, "KRW"));

        RuleResult result = rule.evaluate(external, internal);
        assertThat(result.isPassed()).isTrue();
        assertThat(result.getScore()).isEqualTo(100);
    }
}
