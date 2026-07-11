package com.github.raonjena99.multi_currency_ledger_service.reconciliation.application.rule;

import org.apache.commons.text.similarity.LevenshteinDistance;
import org.springframework.stereotype.Component;

import com.github.raonjena99.multi_currency_ledger_service.reconciliation.domain.ExternalSettlement;
import com.github.raonjena99.multi_currency_ledger_service.reconciliation.infrastructure.query.InternalTransactionCandidate;

/**
 * 텍스트 유사도를 기반으로 ExternalSettlement(외부 정산)와 InternalTransactionCandidate(내부 거래 후보)를 대조하는 규칙(MatchingRule)입니다.
 * LevenshteinDistance(레벤슈타인 거리) 알고리즘을 사용하여 문자열의 유사도를 평가합니다.
 */
@Component
public class FuzzyTextMatchingRule implements MatchingRule {
    private final LevenshteinDistance levenshtein = LevenshteinDistance.getDefaultInstance();

    /**
     * 규칙의 실행 우선순위를 반환합니다.
     * 
     * @return 우선순위 값
     */
    @Override public int getOrder() { return 3; }

    /**
     * 주어진 외부 정산 내역과 내부 거래 후보 간의 텍스트 유사도를 평가합니다.
     * 
     * @param external 외부 정산 내역 (ExternalSettlement)
     * @param internal 내부 거래 후보 (InternalTransactionCandidate)
     * @return 규칙 평가 결과 (RuleResult)
     */
    @Override
    public RuleResult evaluate(ExternalSettlement external, InternalTransactionCandidate internal) {
        // 영문자, 숫자, 한글을 제외한 모든 특수문자 및 공백을 제거하고 대문자로 변환하여 비교를 단순화합니다.
        String extDesc = external.getDescription().replaceAll("[^a-zA-Z0-9가-힣]", "").toUpperCase();
        String intDesc = internal.description().replaceAll("[^a-zA-Z0-9가-힣]", "").toUpperCase();
        
        // 두 문자열 중 더 긴 길이를 기준 길이로 설정합니다.
        int maxLength = Math.max(extDesc.length(), intDesc.length());
        
        // 두 문자열 모두 길이가 0인 경우, 내용이 없는 것으로 간주하여 100점(완전 일치)으로 통과시킵니다.
        if (maxLength == 0) return RuleResult.builder().passed(true).score(100).build();

        // 레벤슈타인 거리를 이용하여 두 문자열의 유사도를 백분율(%) 점수로 계산합니다.
        int score = (int) (((double) (maxLength - levenshtein.apply(extDesc, intDesc)) / maxLength) * 100);
        
        // 계산된 점수가 75점 이상인 경우 규칙을 통과(passed)한 것으로 간주합니다.
        if (score >= 75) return RuleResult.builder().passed(true).score(score).build();
        
        // 75점 미만인 경우 실패 사유(failReason)와 함께 규칙 실패를 반환합니다.
        return RuleResult.builder().passed(false).failReason("TEXT_NOT_FOUND").build();
    }
}
