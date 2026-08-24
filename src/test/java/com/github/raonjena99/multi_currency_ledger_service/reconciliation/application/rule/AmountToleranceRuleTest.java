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

    private final AmountToleranceRule rule = new AmountToleranceRule(new java.math.BigDecimal("0.005"), new java.math.BigDecimal("100"));

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
        // 오차가 작을수록 높은 점수를 주어 여러 후보 중 가장 가까운 건이 선택되게 한다.
        // 오차 50, 허용 100 이므로 만점은 아니다.
        assertThat(result.getScore()).isBetween(50, 100);
    }

    @Test
    @org.junit.jupiter.api.DisplayName("허용 오차는 통화에 비례한다: BTC 100 차이는 통과하지 않는다")
    void tolerance_is_currency_aware() {
        // 이전 구현은 통화와 무관한 상수 100 을 비교해 100 BTC 차이도 '일치'로 판정했다.
        ExternalSettlement ext = ExternalSettlement.create("ext-btc", "PG", OffsetDateTime.now(), "desc",
                Money.of("200", AssetType.CRYPTO, "BTC"));
        InternalTransactionCandidate internal = new InternalTransactionCandidate(
                java.util.UUID.randomUUID(), java.util.UUID.randomUUID(), OffsetDateTime.now(), "desc",
                Money.of("100", AssetType.CRYPTO, "BTC"));

        RuleResult result = rule.evaluate(ext, internal);

        assertThat(result.isPassed())
                .as("100 BTC 차이가 허용 오차로 취급되면 안 된다")
                .isFalse();
        assertThat(result.getFailReason()).isEqualTo("AMOUNT_MISMATCH");
    }

    @Test
    @org.junit.jupiter.api.DisplayName("큰 금액에서는 비율 오차가 적용된다")
    void ratio_tolerance_applies_to_large_amounts() {
        // 10,000,000 KRW 의 0.5% = 50,000 KRW 까지 허용
        ExternalSettlement ext = ExternalSettlement.create("ext-big", "PG", OffsetDateTime.now(), "desc",
                Money.of("10000000", AssetType.FIAT, "KRW"));
        InternalTransactionCandidate internal = new InternalTransactionCandidate(
                java.util.UUID.randomUUID(), java.util.UUID.randomUUID(), OffsetDateTime.now(), "desc",
                Money.of("9970000", AssetType.FIAT, "KRW"));

        assertThat(rule.evaluate(ext, internal).isPassed()).isTrue();
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
