package com.github.raonjena99.multi_currency_ledger_service.reconciliation.application.rule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
    void evaluate_should_pass_with_low_score_when_descriptions_are_different() {
        // 텍스트 규칙은 필수 관문이 아니라 순위 결정용 보조 점수다. 적재 경로가 저장하는
        // 합성 설명과 내부 원장 설명은 구조적으로 유사도가 낮아, 관문으로 쓰면
        // 자동 적재된 정산이 전부 데드레터가 된다.
        ExternalSettlement external = ExternalSettlement.create(
            "ext-1", "PG1", OffsetDateTime.now(), "Buy Apple Stock", Money.of("10", AssetType.FIAT, "USD")
        );
        InternalTransactionCandidate internal = new InternalTransactionCandidate(
            UUID.randomUUID(), UUID.randomUUID(), OffsetDateTime.now(), "Sell Microsoft Stock", Money.of("10", AssetType.FIAT, "USD")
        );

        RuleResult result = rule.evaluate(external, internal);
        assertThat(result.isPassed()).isTrue();
        assertThat(result.getScore()).isLessThan(75);
    }

    @Test
    void evaluate_should_rank_similar_description_above_different_one() {
        ExternalSettlement external = ExternalSettlement.create(
            "ext-1", "PG1", OffsetDateTime.now(), "Buy Apple Stock 123", Money.of("10", AssetType.FIAT, "USD")
        );
        InternalTransactionCandidate similar = new InternalTransactionCandidate(
            UUID.randomUUID(), UUID.randomUUID(), OffsetDateTime.now(), "BUY APPLE STK 123", Money.of("10", AssetType.FIAT, "USD")
        );
        InternalTransactionCandidate different = new InternalTransactionCandidate(
            UUID.randomUUID(), UUID.randomUUID(), OffsetDateTime.now(), "Sell Microsoft Stock", Money.of("10", AssetType.FIAT, "USD")
        );

        assertThat(rule.evaluate(external, similar).getScore())
                .isGreaterThan(rule.evaluate(external, different).getScore());
    }

    @Test
    void evaluate_should_pass_neutrally_when_descriptions_are_empty_or_null() {
        // 내용이 없으면 텍스트로는 아무것도 판정할 수 없다. 과거처럼 100점(완전 일치)을 주면
        // 내용 없는 두 레코드가 실제 설명이 있는 후보보다 높은 순위를 차지한다.
        ExternalSettlement external = mock(ExternalSettlement.class);
        when(external.getDescription()).thenReturn(null);

        InternalTransactionCandidate internal = new InternalTransactionCandidate(
            UUID.randomUUID(), UUID.randomUUID(), OffsetDateTime.now(), null, Money.of("10", AssetType.FIAT, "USD")
        );

        RuleResult result = rule.evaluate(external, internal);
        assertThat(result.isPassed()).isTrue();
        assertThat(result.getScore()).isZero();
    }
}
