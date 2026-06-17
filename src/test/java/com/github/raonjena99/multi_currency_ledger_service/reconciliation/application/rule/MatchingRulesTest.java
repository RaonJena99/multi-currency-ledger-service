package com.github.raonjena99.multi_currency_ledger_service.reconciliation.application.rule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.github.raonjena99.multi_currency_ledger_service.common.domain.Money;
import com.github.raonjena99.multi_currency_ledger_service.common.model.AssetType;
import com.github.raonjena99.multi_currency_ledger_service.reconciliation.domain.ExternalSettlement;
import com.github.raonjena99.multi_currency_ledger_service.reconciliation.infrastructure.query.InternalTransactionCandidate;

class MatchingRulesTest {

    private final TimeToleranceRule timeRule = new TimeToleranceRule();
    private final AmountToleranceRule amountRule = new AmountToleranceRule();
    private final FuzzyTextMatchingRule textRule = new FuzzyTextMatchingRule();

    @Test
    @DisplayName("[TimeRule] 결제일 기준 ±3 영업일 이내면 매칭에 통과한다")
    void timeTolerance_Pass() {
        // Given
        ExternalSettlement ext = mockSettlement(OffsetDateTime.parse("2026-06-15T10:00:00Z"), "TOSS", "1000");
        InternalTransactionCandidate cand = mockCandidate(OffsetDateTime.parse("2026-06-18T10:00:00Z"), "TOSS", "1000");

        // When
        RuleResult result = timeRule.evaluate(ext, cand);

        // Then
        assertThat(result.isPassed()).isTrue();
    }

    @Test
    @DisplayName("[AmountRule] 100원 이하의 수수료 차이는 매칭에 통과한다")
    void amountTolerance_Pass() {
        // Given
        ExternalSettlement ext = mockSettlement(OffsetDateTime.now(), "DESC", "950");
        InternalTransactionCandidate cand = mockCandidate(OffsetDateTime.now(), "DESC", "1000");

        // When
        RuleResult result = amountRule.evaluate(ext, cand);

        // Then
        assertThat(result.isPassed()).isTrue();
    }

    @Test
    @DisplayName("[TextRule] 레벤슈타인 유사도 75% 이상이면 매칭에 통과한다")
    void fuzzyTextMatching_Pass() {
        // Given
        ExternalSettlement ext = mockSettlement(OffsetDateTime.now(), "TOSS_PAYMENTS_0615", "1000");
        InternalTransactionCandidate cand = mockCandidate(OffsetDateTime.now(), "TOSSPAYMENT0615", "1000");

        // When
        RuleResult result = textRule.evaluate(ext, cand);

        // Then
        assertThat(result.isPassed()).isTrue();
        assertThat(result.getScore()).isGreaterThanOrEqualTo(75);
    }

    // --- Test Helpers ---
    private ExternalSettlement mockSettlement(OffsetDateTime date, String desc, String amount) {
        ExternalSettlement mock = Mockito.mock(ExternalSettlement.class);
        when(mock.getSettlementDate()).thenReturn(date);
        when(mock.getDescription()).thenReturn(desc);
        when(mock.getAmount()).thenReturn(Money.of(amount, AssetType.FIAT));
        return mock;
    }

    private InternalTransactionCandidate mockCandidate(OffsetDateTime date, String desc, String amount) {
        return new InternalTransactionCandidate(
                UUID.randomUUID(), date, desc, Money.of(amount, AssetType.FIAT)
        );
    }
}
