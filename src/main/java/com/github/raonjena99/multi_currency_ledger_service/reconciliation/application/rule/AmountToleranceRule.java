package com.github.raonjena99.multi_currency_ledger_service.reconciliation.application.rule;

import java.math.BigDecimal;

import org.springframework.stereotype.Component;

import com.github.raonjena99.multi_currency_ledger_service.reconciliation.domain.ExternalSettlement;
import com.github.raonjena99.multi_currency_ledger_service.reconciliation.infrastructure.query.InternalTransactionCandidate;

/**
 * 금액을 기준으로 ExternalSettlement(외부 정산)와 InternalTransactionCandidate(내부 거래 후보)를 대조하는 규칙(MatchingRule)입니다.
 * 수수료 등의 차이로 인해 발생할 수 있는 허용 오차 금액(Amount Tolerance) 이내인지 검사합니다.
 */
@Component
public class AmountToleranceRule implements MatchingRule {
    private static final BigDecimal TOLERANCE = new BigDecimal("100");

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
        // 외부 정산 금액과 내부 거래 금액의 절대적인 차이값을 계산합니다.
        BigDecimal diff = external.getAmount().subtract(internal.amount()).getAmount().abs();
        
        // 계산된 차이값이 허용 오차(TOLERANCE)인 100 이하인 경우 규칙을 통과시킵니다.
        if (diff.compareTo(TOLERANCE) <= 0) return RuleResult.builder().passed(true).score(100).build();
        
        // 허용 오차를 초과하는 경우 실패 사유(failReason)와 함께 규칙 실패를 반환합니다.
        return RuleResult.builder().passed(false).failReason("AMOUNT_MISMATCH").build();
    }
}
