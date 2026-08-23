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
        // [수정] 규칙들이 우선순위(getOrder)대로 정렬되지 않아, 무거운 연산(레벤슈타인 거리)이 먼저 실행되는 심각한 성능 비효율 방지
        this.rules = new ArrayList<>(rules);
        this.rules.sort(java.util.Comparator.comparingInt(MatchingRule::getOrder));
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

        // 리더(Reader)의 페이징 오프셋 밀림을 방지하기 위해 전체 데이터를 가져오되, 
        // PENDING 상태가 아닌 이미 처리된 건들은 프로세서에서 솎아내어(null 반환) 다음 단계로 넘기지 않습니다.
        if (external.getStatus() != com.github.raonjena99.multi_currency_ledger_service.common.model.SettlementStatus.PENDING) {
            return null;
        }

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
            final UUID bestMatchId = bestMatch.transactionId();
            
            // [수정] Collections.synchronizedMap의 values 순회 시 발생할 수 있는 ConcurrentModificationException 방어
            synchronized (dailyCandidatesCache) {
                dailyCandidatesCache.values().forEach(list -> list.removeIf(c -> c.transactionId().equals(bestMatchId)));
            }
            
            Money feeDifference = external.getAmount().subtract(bestMatch.amount());
            return new MatchedReconciliationResult(external, bestMatch.transactionId(), bestMatch.accountId(), feeDifference);
        }

        throw new UnmatchableSettlementException(lastFailReason, external.getId().toString());
    }

    /**
     * DB 저장(Writer) 단계 등에서 예외가 발생하여 청크 단위 롤백이 일어날 경우 호출됩니다.
     * 메모리 상의 캐시는 자동 롤백되지 않으므로, 캐시를 완전히 초기화하여 재시도 시 후보군 누수(DLQ 유입)를 방지합니다.
     */
    @org.springframework.batch.core.annotation.AfterChunkError
    public void afterChunkError(org.springframework.batch.core.scope.context.ChunkContext context) {
        log.warn("Chunk error detected, clearing candidates cache to prevent state corruption on retry.");
        dailyCandidatesCache.clear();
    }
}