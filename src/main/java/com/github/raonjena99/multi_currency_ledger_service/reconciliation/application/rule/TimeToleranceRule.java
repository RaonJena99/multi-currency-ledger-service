package com.github.raonjena99.multi_currency_ledger_service.reconciliation.application.rule;

import java.time.temporal.ChronoUnit;

import org.springframework.stereotype.Component;

import com.github.raonjena99.multi_currency_ledger_service.reconciliation.domain.ExternalSettlement;
import com.github.raonjena99.multi_currency_ledger_service.reconciliation.infrastructure.query.InternalTransactionCandidate;

/**
 * 발생 시간을 기준으로 ExternalSettlement(외부 정산)와 InternalTransactionCandidate(내부 거래 후보)를 대조하는 규칙(MatchingRule)입니다.
 * 허용 오차 시간(Time Tolerance) 내에 발생하는지 검사합니다.
 */
@Component
public class TimeToleranceRule implements MatchingRule {
    
    /**
     * 규칙의 실행 우선순위를 반환합니다.
     * 
     * @return 우선순위 값
     */
    @Override public int getOrder() { return 1; } 

    /**
     * 외부 정산 내역과 내부 거래 후보의 일자 차이를 평가합니다.
     * 
     * @param external 외부 정산 내역 (ExternalSettlement)
     * @param internal 내부 거래 후보 (InternalTransactionCandidate)
     * @return 규칙 평가 결과 (RuleResult)
     */
    @Override
    public RuleResult evaluate(ExternalSettlement external, InternalTransactionCandidate internal) {
        // 외부 정산 일자와 내부 거래 일자 사이의 절대적인 일(days) 수 차이를 계산합니다.
        long diffDays = Math.abs(ChronoUnit.DAYS.between(
            external.getSettlementDate().toLocalDate(), 
            internal.transactedAt().toLocalDate()
        ));
        
        // 두 일자의 차이가 3일 이하인 경우 허용 오차 범위 내로 간주하여 규칙을 통과시킵니다.
        if (diffDays <= 3) return RuleResult.builder().passed(true).score(100).build();
        
        // 3일을 초과하는 경우 실패 사유(failReason)와 함께 규칙 실패를 반환합니다.
        return RuleResult.builder().passed(false).failReason("TIME_WINDOW_EXCEEDED").build();
    }
}
