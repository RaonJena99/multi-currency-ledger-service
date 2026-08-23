package com.github.raonjena99.multi_currency_ledger_service.reconciliation.application.rule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.github.raonjena99.multi_currency_ledger_service.common.domain.Money;
import com.github.raonjena99.multi_currency_ledger_service.common.model.AssetType;
import com.github.raonjena99.multi_currency_ledger_service.reconciliation.domain.ExternalSettlement;
import com.github.raonjena99.multi_currency_ledger_service.reconciliation.infrastructure.query.InternalTransactionCandidate;

class FuzzyTextMatchingRuleTest {

    private final FuzzyTextMatchingRule rule = new FuzzyTextMatchingRule();

    @Test
    void getOrder_should_return_3() {
        assertThat(rule.getOrder()).isEqualTo(3);
    }

    @Test
    void evaluate_should_pass_when_descriptions_are_similar() {
        ExternalSettlement external = ExternalSettlement.create(
            "ext-1", "PG1", OffsetDateTime.now(), "Buy Apple Stock 123", Money.of("10", AssetType.FIAT, "USD")
        );
        InternalTransactionCandidate internal = new InternalTransactionCandidate(
            UUID.randomUUID(), UUID.randomUUID(), OffsetDateTime.now(), "BUY APPLE STK 123", Money.of("10", AssetType.FIAT, "USD")
        );

        RuleResult result = rule.evaluate(external, internal);
        assertThat(result.isPassed()).isTrue();
        assertThat(result.getScore()).isGreaterThanOrEqualTo(75);
    }

    @Test
    void evaluate_should_fail_when_descriptions_are_different() {
        ExternalSettlement external = ExternalSettlement.create(
            "ext-1", "PG1", OffsetDateTime.now(), "Buy Apple Stock", Money.of("10", AssetType.FIAT, "USD")
        );
        InternalTransactionCandidate internal = new InternalTransactionCandidate(
            UUID.randomUUID(), UUID.randomUUID(), OffsetDateTime.now(), "Sell Microsoft Stock", Money.of("10", AssetType.FIAT, "USD")
        );

        RuleResult result = rule.evaluate(external, internal);
        assertThat(result.isPassed()).isFalse();
        assertThat(result.getFailReason()).isEqualTo("TEXT_NOT_FOUND");
    }

    @Test
    void evaluate_should_pass_when_both_descriptions_are_empty_or_null() {
        // Mock to return null description since create() requires non-null description
        ExternalSettlement external = mock(ExternalSettlement.class);
        when(external.getDescription()).thenReturn(null);
        
        InternalTransactionCandidate internal = new InternalTransactionCandidate(
            UUID.randomUUID(), UUID.randomUUID(), OffsetDateTime.now(), null, Money.of("10", AssetType.FIAT, "USD")
        );

        RuleResult result = rule.evaluate(external, internal);
        assertThat(result.isPassed()).isTrue();
        assertThat(result.getScore()).isEqualTo(100);
    }
}
