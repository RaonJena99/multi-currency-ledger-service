package com.github.raonjena99.multi_currency_ledger_service.reconciliation.application.rule;

import lombok.Builder;
import lombok.Getter;

/**
 * 대사 매칭 규칙(MatchingRule)의 평가 결과를 담는 DTO(Data Transfer Object) 클래스입니다.
 */
@Getter 
@Builder
public class RuleResult {
    private final boolean passed;
    private final int score;
    private final String failReason;
}
