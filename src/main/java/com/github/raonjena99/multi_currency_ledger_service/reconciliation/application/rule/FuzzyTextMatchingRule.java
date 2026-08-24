package com.github.raonjena99.multi_currency_ledger_service.reconciliation.application.rule;

import org.apache.commons.text.similarity.LevenshteinDistance;
import org.springframework.stereotype.Component;

import com.github.raonjena99.multi_currency_ledger_service.reconciliation.domain.ExternalSettlement;
import com.github.raonjena99.multi_currency_ledger_service.reconciliation.infrastructure.query.InternalTransactionCandidate;

/**
 * 텍스트 유사도를 기반으로 ExternalSettlement(외부 정산)와 InternalTransactionCandidate(내부 거래 후보)를 대조하는 규칙(MatchingRule)입니다.
 * LevenshteinDistance(레벤슈타인 거리) 알고리즘을 사용하여 문자열의 유사도를 평가합니다.
 *
 * <p><b>이 규칙은 통과/실패 관문이 아니라 순위 결정용 보조 점수입니다.</b>
 * 외부 정산 설명은 적재 시 합성 문자열("PG settlement &lt;id&gt; (&lt;status&gt;)")로 저장되고
 * 내부 원장 설명은 별도 형식("Auto-recorded via ACL. Ref TradeID: ...")이므로, 정상 매칭조차
 * 유사도가 구조적으로 낮습니다. 이를 필수 관문으로 두면 자동 적재된 정산 건이 전부
 * TEXT_NOT_FOUND 로 데드레터가 됩니다. 실제 판정은 금액·시간 규칙이 담당하고,
 * 텍스트 유사도는 동점 후보 간 순위만 가릅니다.
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
        String rawExtDesc = external.getDescription() != null ? external.getDescription() : "";
        String rawIntDesc = internal.description() != null ? internal.description() : "";

        // 영문자, 숫자, 한글을 제외한 모든 특수문자 및 공백을 제거하고 대문자로 변환하여 비교를 단순화합니다.
        String extDesc = rawExtDesc.replaceAll("[^a-zA-Z0-9가-힣]", "").toUpperCase();
        String intDesc = rawIntDesc.replaceAll("[^a-zA-Z0-9가-힣]", "").toUpperCase();
        
        // 두 문자열 중 더 긴 길이를 기준 길이로 설정합니다.
        int maxLength = Math.max(extDesc.length(), intDesc.length());

        // 어느 한쪽이라도 내용이 없으면 텍스트로는 아무것도 판정할 수 없다.
        // 과거에는 양쪽 모두 비어 있으면 100점(완전 일치)을 주었는데, 그 결과 내용 없는
        // 두 레코드가 실제 설명이 있는 후보보다 높은 순위를 차지했다. 중립(0점)으로 통과시킨다.
        if (extDesc.isEmpty() || intDesc.isEmpty()) {
            return RuleResult.builder().passed(true).score(0).build();
        }

        // 레벤슈타인 거리를 이용하여 두 문자열의 유사도를 백분율(%) 점수로 계산합니다.
        int score = (int) (((double) (maxLength - levenshtein.apply(extDesc, intDesc)) / maxLength) * 100);

        // 유사도와 무관하게 항상 통과시키고, 점수만 반영해 후보 간 순위를 가린다.
        // 금액(AmountToleranceRule)·시간(TimeToleranceRule) 규칙이 실제 관문 역할을 한다.
        return RuleResult.builder().passed(true).score(score).build();
    }
}
