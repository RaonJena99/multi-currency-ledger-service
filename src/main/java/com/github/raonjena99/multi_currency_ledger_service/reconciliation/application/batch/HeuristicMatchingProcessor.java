package com.github.raonjena99.multi_currency_ledger_service.reconciliation.application.batch;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.infrastructure.item.ItemProcessor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.github.raonjena99.multi_currency_ledger_service.common.domain.Money;
import com.github.raonjena99.multi_currency_ledger_service.reconciliation.application.exception.UnmatchableSettlementException;
import com.github.raonjena99.multi_currency_ledger_service.reconciliation.application.rule.MatchingRule;
import com.github.raonjena99.multi_currency_ledger_service.reconciliation.application.rule.RuleResult;
import com.github.raonjena99.multi_currency_ledger_service.reconciliation.domain.ExternalSettlement;
import com.github.raonjena99.multi_currency_ledger_service.reconciliation.infrastructure.query.InternalTransactionCandidate;
import com.github.raonjena99.multi_currency_ledger_service.reconciliation.infrastructure.query.InternalTransactionQueryDao;

import lombok.extern.slf4j.Slf4j;

/**
 * 휴리스틱 기반의 규칙들(MatchingRule)을 적용하여 ExternalSettlement(외부 정산)와 InternalTransactionCandidate(내부 거래 후보)를
 * 매칭하는 Spring Batch의 ItemProcessor(아이템 프로세서)입니다.
 */
@Slf4j
@StepScope
@Component
public class HeuristicMatchingProcessor implements ItemProcessor<ExternalSettlement, MatchedReconciliationResult> {

    private final InternalTransactionQueryDao queryDao;
    private final List<MatchingRule> rules;
    private final String startOfMonthStr;

    private final Set<UUID> matchedTransactionIds = ConcurrentHashMap.newKeySet();

    private final Map<LocalDate, List<InternalTransactionCandidate>> dailyCandidatesCache = 
        Collections.synchronizedMap(
            new LinkedHashMap<LocalDate, List<InternalTransactionCandidate>>(16, 0.75f, true) {

                @Override
                protected boolean removeEldestEntry(Map.Entry<LocalDate, List<InternalTransactionCandidate>> eldest) {
                    return size() > 14; 
                }
            }
        );
    private LocalDate latestTargetDate = null;

    public HeuristicMatchingProcessor(
            InternalTransactionQueryDao queryDao,
            List<MatchingRule> rules,
            @Value("#{jobParameters['startOfMonth']}") String startOfMonthStr) {
        this.queryDao = queryDao;
        this.rules = rules;
        this.startOfMonthStr = startOfMonthStr;
    }

    private List<InternalTransactionCandidate> getCandidatesForDate(LocalDate date) {
        return dailyCandidatesCache.computeIfAbsent(date, d -> {
            log.debug("Lazy loading internal transaction candidates for date: {}", d);
            OffsetDateTime startOfDay = d.atStartOfDay().atOffset(java.time.ZoneOffset.UTC);
            OffsetDateTime endOfDay = startOfDay.plusDays(1);
            return queryDao.fetchCandidatesForPeriod(startOfDay, endOfDay);
        });
    }

    /**
     * 외부 정산 데이터 1건을 입력받아, 메모리에 캐싱된 내부 거래 후보들과 대조하여 최적의 매칭 결과를 반환합니다.
     * 모든 규칙을 통과하고 점수가 가장 높은 후보를 선택합니다.
     * 
     * @param external 외부 정산 데이터 (ExternalSettlement)
     * @return 성공적으로 매칭된 결과 (MatchedReconciliationResult)
     * @throws UnmatchableSettlementException 매칭되는 후보가 없을 경우 (예외 발생 시 DLQ로 이동)
     */
    @Override
    public MatchedReconciliationResult process(ExternalSettlement external) {

        LocalDate targetDate = external.getSettlementDate().toLocalDate();
        
        List<InternalTransactionCandidate> searchSpace = new ArrayList<>();

        // 대상 일자의 전후 3일(총 7일) 범위 내에 있는 거래 후보들을 검색 공간(searchSpace)으로 구성합니다.
        for (int i = -3; i <= 3; i++) {
            searchSpace.addAll(getCandidatesForDate(targetDate.plusDays(i)));
        }

        InternalTransactionCandidate bestMatch = null;
        int highestScore = -1;
        String lastFailReason = "TIME_WINDOW_EXCEEDED";

        // 검색 공간의 모든 후보들을 순회하면서 규칙들을 평가합니다.
        for (InternalTransactionCandidate candidate : searchSpace) {

            if (matchedTransactionIds.contains(candidate.transactionId())) {
                continue;
            }

            boolean allPassed = true;
            int totalScore = 0;

            for (MatchingRule rule : rules) {
                // 각 매칭 규칙(MatchingRule)을 평가합니다.
                RuleResult result = rule.evaluate(external, candidate);
                if (!result.isPassed()) {
                    // 하나의 규칙이라도 통과하지 못하면 해당 후보는 실패로 처리하고 다음 후보를 검사합니다.
                    lastFailReason = result.getFailReason();
                    allPassed = false;
                    break;
                }
                totalScore += result.getScore();
            }

            // 모든 규칙을 통과하고, 기존 최고 점수(highestScore)보다 높은 점수를 얻은 경우 최고 후보로 갱신합니다.
            if (allPassed && totalScore > highestScore) {
                highestScore = totalScore;
                bestMatch = candidate;
            }
        }

        if (bestMatch != null) {
            if (!matchedTransactionIds.add(bestMatch.transactionId())) {
                throw new UnmatchableSettlementException("CONCURRENT_MATCH_CONFLICT", external.getId().toString());
            }
            
            Money feeDifference = external.getAmount().subtract(bestMatch.amount());
            return new MatchedReconciliationResult(external, bestMatch.transactionId(), feeDifference);
        }

        throw new UnmatchableSettlementException(lastFailReason, external.getId().toString());
    }
}