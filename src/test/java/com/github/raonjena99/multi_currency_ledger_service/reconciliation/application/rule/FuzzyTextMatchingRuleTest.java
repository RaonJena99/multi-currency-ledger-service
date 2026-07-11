package com.github.raonjena99.multi_currency_ledger_service.reconciliation.application.rule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.github.raonjena99.multi_currency_ledger_service.common.domain.Money;
import com.github.raonjena99.multi_currency_ledger_service.common.model.AssetType;
import com.github.raonjena99.multi_currency_ledger_service.reconciliation.domain.ExternalSettlement;
import com.github.raonjena99.multi_currency_ledger_service.reconciliation.infrastructure.query.InternalTransactionCandidate;

class FuzzyTextMatchingRuleTest {

    @Test
    @DisplayName("텍스트 매칭 - maxLength가 0일 때 무조건 통과")
    void evaluate_empty() {
        FuzzyTextMatchingRule rule = new FuzzyTextMatchingRule();
        
        ExternalSettlement ext = ExternalSettlement.create("REF1", "TOSS", OffsetDateTime.now(), "!!", Money.of("10", AssetType.FIAT, "KRW"));
        InternalTransactionCandidate cand = new InternalTransactionCandidate(UUID.randomUUID(), OffsetDateTime.now(), "@@", Money.of("10", AssetType.FIAT, "KRW"));
        
        RuleResult result = rule.evaluate(ext, cand);
        assertThat(result.isPassed()).isTrue();
    }

    @Test
    @DisplayName("텍스트 매칭 - score >= 75일 때 통과")
    void evaluate_passed() {
        FuzzyTextMatchingRule rule = new FuzzyTextMatchingRule();
        
        ExternalSettlement ext = ExternalSettlement.create("REF1", "TOSS", OffsetDateTime.now(), "TOSS PAY", Money.of("10", AssetType.FIAT, "KRW"));
        InternalTransactionCandidate cand = new InternalTransactionCandidate(UUID.randomUUID(), OffsetDateTime.now(), "TOSS PAY", Money.of("10", AssetType.FIAT, "KRW"));
        
        RuleResult result = rule.evaluate(ext, cand);
        assertThat(result.isPassed()).isTrue();
    }

    @Test
    @DisplayName("텍스트 매칭 - score < 75일 때 실패")
    void evaluate_failed() {
        FuzzyTextMatchingRule rule = new FuzzyTextMatchingRule();
        
        ExternalSettlement ext = ExternalSettlement.create("REF1", "TOSS", OffsetDateTime.now(), "TOSS PAY", Money.of("10", AssetType.FIAT, "KRW"));
        InternalTransactionCandidate cand = new InternalTransactionCandidate(UUID.randomUUID(), OffsetDateTime.now(), "NAVER PAY", Money.of("10", AssetType.FIAT, "KRW"));
        
        RuleResult result = rule.evaluate(ext, cand);
        assertThat(result.isPassed()).isFalse();
        assertThat(result.getFailReason()).isEqualTo("TEXT_NOT_FOUND");
    }
}
