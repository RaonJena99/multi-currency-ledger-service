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

class TimeToleranceRuleTest {

    @Test
    @DisplayName("시간 오차 검증 - 3일 이내")
    void evaluate_passed() {
        TimeToleranceRule rule = new TimeToleranceRule();
        
        ExternalSettlement ext = ExternalSettlement.create("REF1", "TOSS", OffsetDateTime.parse("2026-07-15T10:00:00Z"), "DESC", Money.of("10", AssetType.FIAT));
        InternalTransactionCandidate cand = new InternalTransactionCandidate(UUID.randomUUID(), OffsetDateTime.parse("2026-07-12T10:00:00Z"), "DESC", Money.of("10", AssetType.FIAT));
        
        RuleResult result = rule.evaluate(ext, cand);
        assertThat(result.isPassed()).isTrue();
    }

    @Test
    @DisplayName("시간 오차 검증 - 3일 초과")
    void evaluate_failed() {
        TimeToleranceRule rule = new TimeToleranceRule();
        
        ExternalSettlement ext = ExternalSettlement.create("REF1", "TOSS", OffsetDateTime.parse("2026-07-15T10:00:00Z"), "DESC", Money.of("10", AssetType.FIAT));
        InternalTransactionCandidate cand = new InternalTransactionCandidate(UUID.randomUUID(), OffsetDateTime.parse("2026-07-11T10:00:00Z"), "DESC", Money.of("10", AssetType.FIAT));
        
        RuleResult result = rule.evaluate(ext, cand);
        assertThat(result.isPassed()).isFalse();
        assertThat(result.getFailReason()).isEqualTo("TIME_WINDOW_EXCEEDED");
    }
}
