package com.github.raonjena99.multi_currency_ledger_service.reconciliation.application.rule;

import java.math.BigDecimal;
import java.math.RoundingMode;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.github.raonjena99.multi_currency_ledger_service.common.domain.CurrencyScaleResolver;
import com.github.raonjena99.multi_currency_ledger_service.reconciliation.domain.ExternalSettlement;
import com.github.raonjena99.multi_currency_ledger_service.reconciliation.infrastructure.query.InternalTransactionCandidate;

/**
 * 금액을 기준으로 ExternalSettlement(외부 정산)와 InternalTransactionCandidate(내부 거래 후보)를 대조하는 규칙입니다.
 *
 * <p>허용 오차를 <b>통화에 독립적인 상수로 두면 안 됩니다.</b> 이전 구현은 {@code 100} 을 그대로
 * 비교했는데, 이 값은 100 KRW(약 0.07 달러)이기도 하고 100 USD 이기도 하고 100 BTC 이기도 합니다.
 * 그래서 암호화폐 정산에서 100 BTC 차이가 "허용 오차 내 일치"로 판정될 수 있었습니다.
 *
 * <p>대신 두 기준을 함께 씁니다.
 * <ul>
 *   <li><b>비율 오차</b>: 정산 금액 대비 일정 비율 이내 (수수료 차감 등 규모에 비례하는 오차)</li>
 *   <li><b>최소 절대 오차</b>: 통화 최소 단위의 배수 이내 (반올림 수준의 소액 오차)</li>
 * </ul>
 * 둘 중 더 큰 값을 허용 한도로 삼습니다.
 */
@Component
public class AmountToleranceRule implements MatchingRule {

    /** 정산 금액 대비 허용 비율. 기본 0.5%. */
    private final BigDecimal toleranceRatio;

    /** 통화 최소 단위의 몇 배까지 절대 오차로 허용할지. 기본 100배(KRW 기준 100원). */
    private final BigDecimal toleranceMinUnits;

    /**
     * 필드 주입 대신 생성자 주입을 사용합니다. 필드에 {@code @Value} 를 달면 단위 테스트에서
     * 직접 생성한 인스턴스의 임계값이 null 이 되어 규칙이 조용히 다르게 동작합니다.
     *
     * @param toleranceRatio    정산 금액 대비 허용 비율
     * @param toleranceMinUnits 통화 최소 단위의 허용 배수
     */
    public AmountToleranceRule(
            @Value("${ledger.reconciliation.amount-tolerance-ratio:0.005}") BigDecimal toleranceRatio,
            @Value("${ledger.reconciliation.amount-tolerance-min-units:100}") BigDecimal toleranceMinUnits) {
        this.toleranceRatio = toleranceRatio;
        this.toleranceMinUnits = toleranceMinUnits;
    }

    /**
     * 규칙의 실행 우선순위를 반환합니다.
     *
     * @return 우선순위 값
     */
    @Override public int getOrder() { return 2; }

    /**
     * 외부 정산 내역과 내부 거래 후보의 금액 차이를 평가합니다.
     *
     * @param external 외부 정산 내역 (ExternalSettlement)
     * @param internal 내부 거래 후보 (InternalTransactionCandidate)
     * @return 규칙 평가 결과 (RuleResult)
     */
    @Override
    public RuleResult evaluate(ExternalSettlement external, InternalTransactionCandidate internal) {
        // 통화와 자산 타입이 다르면 Money 연산이 예외를 던지므로 즉시 불일치로 판정한다.
        if (external.getAmount().getAssetType() != internal.amount().getAssetType() ||
            !external.getAmount().getCurrencyCode().equals(internal.amount().getCurrencyCode())) {
            return RuleResult.builder().passed(false).failReason("CURRENCY_MISMATCH").build();
        }

        BigDecimal diff = external.getAmount().subtract(internal.amount()).getAmount().abs();
        BigDecimal tolerance = resolveTolerance(external);

        if (diff.compareTo(tolerance) <= 0) {
            // 오차가 작을수록 높은 점수를 주어, 여러 후보 중 가장 가까운 건이 선택되도록 한다.
            return RuleResult.builder().passed(true).score(scoreFor(diff, tolerance)).build();
        }

        return RuleResult.builder().passed(false).failReason("AMOUNT_MISMATCH").build();
    }

    private BigDecimal resolveTolerance(ExternalSettlement external) {
        var amount = external.getAmount();

        BigDecimal ratioTolerance = amount.getAmount().abs().multiply(toleranceRatio);
        BigDecimal absoluteTolerance = CurrencyScaleResolver
                .minimumUnit(amount.getAssetType(), amount.getCurrencyCode())
                .multiply(toleranceMinUnits);

        return ratioTolerance.max(absoluteTolerance);
    }

    private int scoreFor(BigDecimal diff, BigDecimal tolerance) {
        if (tolerance.compareTo(BigDecimal.ZERO) <= 0) {
            return 100;
        }
        BigDecimal ratio = diff.divide(tolerance, 4, RoundingMode.HALF_UP);
        int penalty = ratio.multiply(BigDecimal.valueOf(50)).intValue();
        return Math.max(50, 100 - penalty);
    }
}
