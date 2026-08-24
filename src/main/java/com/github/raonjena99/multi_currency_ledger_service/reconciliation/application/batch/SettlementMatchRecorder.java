package com.github.raonjena99.multi_currency_ledger_service.reconciliation.application.batch;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.github.raonjena99.multi_currency_ledger_service.reconciliation.domain.SettlementMatch;
import com.github.raonjena99.multi_currency_ledger_service.reconciliation.infrastructure.SettlementMatchRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 대사 매칭 결과를 독립된 트랜잭션에서 기록하는 컴포넌트입니다.
 *
 * <p>독립 트랜잭션을 쓰는 이유는 두 가지입니다.
 * <ul>
 *   <li>제약조건 위반이 호출자(청크) 트랜잭션을 오염시켜 정상 데이터까지 롤백시키는 것을 막습니다.
 *       {@code save()} 만 쓰면 위반이 flush 시점(커밋 단계)에 터져 호출자의 try-catch 밖에서
 *       발생하므로 청크 전체가 통째로 롤백됩니다.</li>
 *   <li>{@code saveAndFlush()} 로 즉시 flush 해 예외가 이 메서드 안에서 발생하게 만듭니다.</li>
 * </ul>
 *
 * <p><b>멱등성이 필수입니다.</b> {@code faultTolerant()} 스텝은 쓰기 실패 시 청크를 롤백하고
 * 건별로 재실행합니다. 이때 매칭 행은 독립 트랜잭션에서 이미 커밋되어 남아 있으므로, 재실행이
 * 자기 자신이 남긴 행 때문에 실패하면 해당 정산은 영구히 매칭되지 못합니다. 그래서 "이미 내가
 * 기록한 것"과 "다른 정산이 선점한 것"을 구분합니다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SettlementMatchRecorder {

    /** 매칭 기록 시도의 결과. */
    public enum MatchOutcome {
        /** 새로 기록했습니다. */
        RECORDED,
        /** 같은 정산으로 이미 기록되어 있습니다. 재실행이므로 성공으로 취급합니다. */
        ALREADY_RECORDED,
        /** 다른 정산이 이 내부 거래를 선점했습니다. 이 건은 매칭할 수 없습니다. */
        TAKEN_BY_ANOTHER;

        /** 정산 상태를 MATCHED 로 전이시켜도 되는 결과인지 여부. */
        public boolean isMatchable() {
            return this != TAKEN_BY_ANOTHER;
        }
    }

    private final SettlementMatchRepository settlementMatchRepository;

    /**
     * 매칭 기록을 새로운 트랜잭션에서 저장하고 즉시 플러시합니다.
     *
     * @param match 저장할 매칭 엔티티
     * @return 기록 결과. 호출자는 {@link MatchOutcome#isMatchable()} 로 진행 여부를 판단합니다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public MatchOutcome recordMatch(SettlementMatch match) {
        // 선행 조회로 재실행 경로를 먼저 걸러낸다.
        // 제약조건 위반이 발생하면 이 트랜잭션은 abort 되어 더 이상 조회할 수 없으므로,
        // 판정에 필요한 읽기는 반드시 쓰기 이전에 해야 한다.
        var existing = settlementMatchRepository.findById(match.getInternalTransactionId());
        if (existing.isPresent()) {
            boolean sameSettlement = existing.get().getExternalSettlementId()
                    .equals(match.getExternalSettlementId());
            return sameSettlement ? MatchOutcome.ALREADY_RECORDED : MatchOutcome.TAKEN_BY_ANOTHER;
        }

        // 이 정산에 이미 "다른" 내부 거래로 기록된 행이 있으면, 롤백된 청크가 남긴 고아 행이다.
        // (매칭이 완결됐다면 정산 상태가 MATCHED 여서 라이터가 이 지점까지 오지 않는다.)
        // 고아 행을 그대로 두면 (1) 이번 삽입이 uk_settlement_match_settlement 위반으로 실패해
        // 이 정산이 영구히 PENDING 으로 방치되고, (2) 고아 행이 참조하는 내부 거래는 어떤 정산과도
        // 매칭될 수 없게 된다. 완결되지 않은 매칭의 잔재이므로 삭제하고 새 매칭을 기록한다.
        settlementMatchRepository.findByExternalSettlementIdAndSettlementDate(
                        match.getExternalSettlementId(), match.getSettlementDate())
                .filter(orphan -> !orphan.getInternalTransactionId().equals(match.getInternalTransactionId()))
                .ifPresent(orphan -> {
                    log.warn("롤백된 이전 실행이 남긴 고아 매칭 행을 정리합니다. settlementId={}, "
                                    + "orphanInternalTransactionId={}, newInternalTransactionId={}",
                            match.getExternalSettlementId(), orphan.getInternalTransactionId(),
                            match.getInternalTransactionId());
                    settlementMatchRepository.delete(orphan);
                    settlementMatchRepository.flush();
                });

        try {
            settlementMatchRepository.saveAndFlush(match);
            return MatchOutcome.RECORDED;
        } catch (DataIntegrityViolationException e) {
            // 선행 조회 이후에 다른 노드가 먼저 삽입한 진짜 경쟁 상황이다.
            // 이 트랜잭션은 이미 abort 되어 재조회가 불가능하므로 보수적으로 선점된 것으로 판정한다.
            // 스케줄러는 지난달만 대상으로 하므로 "다음 주기 재평가"는 없다. 호출자(라이터)가
            // 이 결과를 데드레터로 격리해 백오피스에서 보이게 만든다.
            log.warn("매칭 기록 경쟁에서 밀렸습니다. internalTransactionId={}, settlementId={}",
                    match.getInternalTransactionId(), match.getExternalSettlementId());
            return MatchOutcome.TAKEN_BY_ANOTHER;
        }
    }
}
