package com.github.raonjena99.multi_currency_ledger_service.reconciliation.application.rule;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import org.junit.jupiter.api.Test;

import com.github.raonjena99.multi_currency_ledger_service.common.domain.Money;
import com.github.raonjena99.multi_currency_ledger_service.common.model.AssetType;
import com.github.raonjena99.multi_currency_ledger_service.reconciliation.domain.ExternalSettlement;
import com.github.raonjena99.multi_currency_ledger_service.reconciliation.infrastructure.query.InternalTransactionCandidate;

class AmountToleranceRuleTest {

    private final AmountToleranceRule rule = new AmountToleranceRule();

    @Test
    void getOrder_should_return_2() {
        assertThat(rule.getOrder()).isEqualTo(2);
    }

    @Test
    void evaluate_should_fail_when_currency_mismatch() {
        ExternalSettlement ext = ExternalSettlement.create("ext1", "PG", OffsetDateTime.now(), "desc", Money.of("1000", AssetType.FIAT, "KRW"));
        InternalTransactionCandidate internal = new InternalTransactionCandidate(java.util.UUID.randomUUID(), java.util.UUID.randomUUID(), OffsetDateTime.now(), "desc", Money.of("1000", AssetType.FIAT, "USD"));
        
        RuleResult result = rule.evaluate(ext, internal);
        assertThat(result.isPassed()).isFalse();
        assertThat(result.getFailReason()).isEqualTo("CURRENCY_MISMATCH");
    }

    @Test
    void evaluate_should_fail_when_assetType_mismatch() {
        ExternalSettlement ext = ExternalSettlement.create("ext1", "PG", OffsetDateTime.now(), "desc", Money.of("1000", AssetType.FIAT, "KRW"));
        InternalTransactionCandidate internal = new InternalTransactionCandidate(java.util.UUID.randomUUID(), java.util.UUID.randomUUID(), OffsetDateTime.now(), "desc", Money.of("1000", AssetType.CRYPTO, "KRW"));
        
        RuleResult result = rule.evaluate(ext, internal);
        assertThat(result.isPassed()).isFalse();
        assertThat(result.getFailReason()).isEqualTo("CURRENCY_MISMATCH");
    }

    @Test
    void evaluate_should_pass_within_tolerance() {
        ExternalSettlement ext = ExternalSettlement.create("ext1", "PG", OffsetDateTime.now(), "desc", Money.of("1050", AssetType.FIAT, "KRW"));
        InternalTransactionCandidate internal = new InternalTransactionCandidate(java.util.UUID.randomUUID(), java.util.UUID.randomUUID(), OffsetDateTime.now(), "desc", Money.of("1000", AssetType.FIAT, "KRW"));
        
        RuleResult result = rule.evaluate(ext, internal);
        assertThat(result.isPassed()).isTrue();
        assertThat(result.getScore()).isEqualTo(100);
    }

    @Test
    void evaluate_should_fail_exceeding_tolerance() {
        ExternalSettlement ext = ExternalSettlement.create("ext1", "PG", OffsetDateTime.now(), "desc", Money.of("1101", AssetType.FIAT, "KRW"));
        InternalTransactionCandidate internal = new InternalTransactionCandidate(java.util.UUID.randomUUID(), java.util.UUID.randomUUID(), OffsetDateTime.now(), "desc", Money.of("1000", AssetType.FIAT, "KRW"));
        
        RuleResult result = rule.evaluate(ext, internal);
        assertThat(result.isPassed()).isFalse();
        assertThat(result.getFailReason()).isEqualTo("AMOUNT_MISMATCH");
    }
}
