package com.github.raonjena99.multi_currency_ledger_service.account.application;

import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;

import com.github.raonjena99.multi_currency_ledger_service.account.domain.MonthlyAccountLedger;
import com.github.raonjena99.multi_currency_ledger_service.account.infrastructure.MonthlyAccountLedgerRepository;
import com.github.raonjena99.multi_currency_ledger_service.common.model.AssetType;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 지정된 월의 MonthlyAccountLedger(월별 계좌 원장)를 조회하거나, 없으면 초기화하고
 * 이전 달 장부를 이월(Carry-forward)하는 역할을 수행합니다.
 *
 * <p>대상 월은 호출자가 {@link LedgerPeriodResolver} 로 확정해 넘겨야 합니다. 한 거래의 자산 원장과
 * 법정화폐 원장이 서로 다른 월에 놓이면 이후 조회가 실패하므로, 월 결정은 계좌 단위로 한 번만
 * 이루어져야 합니다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MonthlyLedgerResolver {

    private final MonthlyAccountLedgerRepository ledgerRepository;
    private final MonthlyLedgerInitializer ledgerInitializer;

    /**
     * 지정된 월의 원장을 찾아 반환하고, 없으면 초기화한 뒤 재조회합니다.
     *
     * @param accountId   계좌 ID
     * @param assetCode   자산 코드
     * @param assetType   자산 유형
     * @param targetMonth 대상 월 ({@code yyyy-MM})
     * @return 해당하는 MonthlyAccountLedger(월별 계좌 원장)
     * @throws IllegalStateException 원장 초기화 후에도 장부를 불러오지 못한 경우
     */
    public MonthlyAccountLedger resolveOrInitializeLedger(UUID accountId, String assetCode,
                                                         AssetType assetType, String targetMonth) {
        return ledgerRepository.findByAccountIdAndAssetCodeAndLedgerMonth(accountId, assetCode, targetMonth)
                .orElseGet(() -> {
                    try {
                        // 원장이 없을 경우, 새로운 트랜잭션을 열어 초기화 작업 수행
                        ledgerInitializer.initializeInNewTransaction(accountId, assetCode, assetType, targetMonth);
                    } catch (DataIntegrityViolationException e) {
                        log.debug("다른 스레드가 이미 해당 월 원장을 생성했습니다. 새로 생성된 원장을 재조회합니다.");
                    }

                    return ledgerRepository.findByAccountIdAndAssetCodeAndLedgerMonth(accountId, assetCode, targetMonth)
                            .orElseThrow(() -> new IllegalStateException(
                                    "Failed to load ledger after initialization: account=" + accountId
                                            + ", asset=" + assetCode + ", month=" + targetMonth));
                });
    }

    /**
     * 원장 행을 읽은 뒤, 그사이 계좌의 최신 원장 월이 대상 월보다 앞서 나가지 않았는지 확인합니다.
     *
     * <p>이월({@code PESSIMISTIC_FORCE_INCREMENT})은 이전 달 행을 <b>이미 읽어 둔</b> 트랜잭션만
     * 커밋 시점 버전 충돌로 막습니다. "최신 월 조회"와 "원장 행 조회" 사이에 이월이 커밋되면,
     * 뒤이은 행 조회는 이미 올라간 버전을 읽으므로 충돌 없이 이전 달에 기장됩니다. 조회 경로는
     * 자산별 최신 월만 읽으므로 그 금액은 보고 잔고에서 사라집니다. 원장 행을 읽은 "뒤"에 한 번 더
     * 확인하면 두 순서가 모두 닫힙니다(읽기 전 이월은 이 검사가, 읽은 뒤 이월은 버전 검사가 잡는다).
     *
     * @param accountId   계좌 ID
     * @param targetMonth 이번 트랜잭션이 기장하려는 월
     * @throws OptimisticLockingFailureException 더 최신 월 원장이 생긴 경우. 호출자의 재시도가 최신 월로 다시 기장한다
     */
    public void requireStillLatestMonth(UUID accountId, String targetMonth) {
        ledgerRepository.findLatestLedgerMonthByAccountId(accountId)
                .filter(latest -> latest.compareTo(targetMonth) > 0)
                .ifPresent(latest -> {
                    throw new OptimisticLockingFailureException(String.format(
                            "기장 대상 월(%s) 확정 뒤 더 최신 원장 월(%s)이 생겼습니다. 최신 월로 다시 처리합니다. account=%s",
                            targetMonth, latest, accountId));
                });
    }
}
