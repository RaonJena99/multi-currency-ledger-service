package com.github.raonjena99.multi_currency_ledger_service.reconciliation.application.rule;

import com.github.raonjena99.multi_currency_ledger_service.reconciliation.domain.ExternalSettlement;
import com.github.raonjena99.multi_currency_ledger_service.reconciliation.infrastructure.query.InternalTransactionCandidate;

/**
 * ExternalSettlement(외부 정산)와 InternalTransactionCandidate(내부 거래 후보)를 매칭하기 위한 규칙을 정의하는 인터페이스(Interface)입니다.
 */
public interface MatchingRule {

    /**
     * 외부 정산 내역과 내부 거래 후보를 비교하여 규칙을 평가합니다.
     *
     * @param external 외부 정산 내역 (ExternalSettlement)
     * @param internal 내부 거래 후보 (InternalTransactionCandidate)
     * @return 규칙 평가 결과 (RuleResult)
     */
    RuleResult evaluate(ExternalSettlement external, InternalTransactionCandidate internal);

    /**
     * 규칙의 실행 우선순위를 반환합니다. 값이 작을수록 먼저 실행됩니다.
     *
     * @return 우선순위 값
     */
    int getOrder(); 
}
