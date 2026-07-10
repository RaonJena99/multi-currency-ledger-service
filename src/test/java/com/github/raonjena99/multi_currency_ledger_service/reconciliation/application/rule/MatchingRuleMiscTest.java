package com.github.raonjena99.multi_currency_ledger_service.reconciliation.application.rule;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
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
        assertThat(new AmountToleranceRule().getOrder()).isEqualTo(2);
        assertThat(new FuzzyTextMatchingRule().getOrder()).isEqualTo(3);
    }

    @Test
    void fuzzyTextMatchingRule_emptyStrings() {
        FuzzyTextMatchingRule rule = new FuzzyTextMatchingRule();
        
        ExternalSettlement external = ExternalSettlement.create("REF1", "TOSS", OffsetDateTime.now(), "!@#", Money.of("1000", AssetType.FIAT));
        InternalTransactionCandidate internal = new InternalTransactionCandidate(java.util.UUID.randomUUID(), OffsetDateTime.now(), "$%^", Money.of("1000", AssetType.FIAT));

        RuleResult result = rule.evaluate(external, internal);
        assertThat(result.passed()).isTrue();
        assertThat(result.score()).isEqualTo(100);
    }
}
