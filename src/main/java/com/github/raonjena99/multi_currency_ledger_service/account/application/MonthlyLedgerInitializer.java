package com.github.raonjena99.multi_currency_ledger_service.account.application;

import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
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
        try {
            // 다른 트랜잭션에 의해 이미 초기화되었는지 재확인 (Double-check)
            if (ledgerRepository.findByAccountIdAndAssetCodeAndLedgerMonth(accountId, assetCode, targetMonth).isPresent()) {
                return; 
            }

            // 가장 최근의 이전 달 MonthlyAccountLedger(원장) 조회
            ledgerRepository.findFirstByAccountIdAndAssetCodeOrderByLedgerMonthDesc(accountId, assetCode)
                .ifPresentOrElse(
                    // 전월 장부가 있을 경우, 해당 잔고 및 평균 단가 정보를 기반으로 당월로 이월(Carry-forward)
                    prevLedger -> {
                        MonthlyAccountLedger rolledOver = MonthlyAccountLedger.carryForwardFrom(prevLedger, targetMonth);
                        ledgerRepository.save(rolledOver); // flush 없이 순수 save
                    },
                    // 전월 장부가 없는 경우, 잔고가 0인 새로운 원장으로 초기화
                    () -> {
                        Account account = accountRepository.findById(accountId)
                                .orElseThrow(() -> new IllegalArgumentException("Account not found: " + accountId));
                        MonthlyAccountLedger newLedger = MonthlyAccountLedger.initialize(accountId, assetCode, assetType, targetMonth, account.getBaseCurrency()); 
                        ledgerRepository.save(newLedger); // flush 없이 순수 save
                    }
                );
        } catch (DataIntegrityViolationException e) {
            // 여러 스레드가 동시에 초기화를 시도하다 발생한 중복 생성(Unique Constraint) 예외 처리
            log.debug("Ledger uniquely initialized by another thread for {} - {}", accountId, assetCode);
        }
    }
}
