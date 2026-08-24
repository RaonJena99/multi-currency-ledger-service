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
        // 정규화 후 내용이 없는 설명은 아무것도 증명하지 못하므로 만점(100)이 아니라
        // 중립(0점)으로 통과해야 한다. 만점을 주면 내용 없는 레코드가 실제 설명이
        // 일치하는 후보를 제치고 선택된다.
        assertThat(result.isPassed()).isTrue();
        assertThat(result.getScore()).isZero();
    }
}
