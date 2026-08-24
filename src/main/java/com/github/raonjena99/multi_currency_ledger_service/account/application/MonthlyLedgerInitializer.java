package com.github.raonjena99.multi_currency_ledger_service.account.application;

import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.github.raonjena99.multi_currency_ledger_service.account.domain.Account;
import com.github.raonjena99.multi_currency_ledger_service.account.domain.MonthlyAccountLedger;
import com.github.raonjena99.multi_currency_ledger_service.account.infrastructure.AccountRepository;
import com.github.raonjena99.multi_currency_ledger_service.account.infrastructure.MonthlyAccountLedgerRepository;
import com.github.raonjena99.multi_currency_ledger_service.common.model.AssetType;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * MonthlyAccountLedger(월별 계좌 원장)를 초기화하고, 필요시 이전 달 장부를 이월(Carry-forward)하는 역할을 수행합니다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MonthlyLedgerInitializer {

    private final MonthlyAccountLedgerRepository ledgerRepository;
    private final AccountRepository accountRepository;

    /**
     * 당월 MonthlyAccountLedger(월별 계좌 원장)를 새로운 트랜잭션 컨텍스트에서 초기화합니다.
     * 동시성 문제 방지를 위해 REQUIRES_NEW 전파 옵션을 사용하며,
     * 이전 달 장부가 존재하면 당월로 이월(Carry-forward) 처리하고, 없으면 신규 원장을 생성합니다.
     *
     * @param accountId 계좌 ID
     * @param assetCode 자산 코드
     * @param assetType 자산 유형
     * @param targetMonth 초기화 대상 월
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void initializeInNewTransaction(UUID accountId, String assetCode, AssetType assetType, String targetMonth) {

        if (ledgerRepository.findByAccountIdAndAssetCodeAndLedgerMonth(accountId, assetCode, targetMonth).isPresent()) {
            return;
        }

        // 이월 원본은 반드시 대상 월보다 "이전" 원장이어야 한다.
        // 필터 없이 최신 원장을 끌어오면 미래 원장의 잔고가 과거 원장의 기초 잔고로 복사된다.
        ledgerRepository
            .findFirstWithLockByAccountIdAndAssetCodeAndLedgerMonthLessThanOrderByLedgerMonthDesc(
                    accountId, assetCode, targetMonth)
            .ifPresentOrElse(
                prevLedger -> {
                    MonthlyAccountLedger rolledOver = MonthlyAccountLedger.carryForwardFrom(prevLedger, targetMonth);
                    ledgerRepository.save(rolledOver);
                },
                () -> {
                    // 계좌 부재는 입력 형식 오류가 아니라 존재하지 않는 리소스이므로 404 로 매핑되는
                    // 전용 예외를 던진다. 이 경로가 거래 흐름에서 가장 먼저 실행되므로,
                    // 여기서 IllegalArgumentException 을 던지면 계좌 부재가 400 으로 나가버린다.
                    Account account = accountRepository.findById(accountId)
                            .orElseThrow(() -> new com.github.raonjena99.multi_currency_ledger_service
                                    .common.exception.AccountNotFoundException(accountId));
                    MonthlyAccountLedger newLedger = MonthlyAccountLedger.initialize(
                            accountId, assetCode, assetType, targetMonth, account.getBaseCurrency());
                    ledgerRepository.save(newLedger);
                }
            );
    }
}
