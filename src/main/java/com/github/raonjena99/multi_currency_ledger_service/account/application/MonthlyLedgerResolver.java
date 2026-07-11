package com.github.raonjena99.multi_currency_ledger_service.account.application;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.github.raonjena99.multi_currency_ledger_service.account.domain.MonthlyAccountLedger;
import com.github.raonjena99.multi_currency_ledger_service.account.domain.Account;
import com.github.raonjena99.multi_currency_ledger_service.account.infrastructure.AccountRepository;
import com.github.raonjena99.multi_currency_ledger_service.account.infrastructure.MonthlyAccountLedgerRepository;
import com.github.raonjena99.multi_currency_ledger_service.common.model.AssetType;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * MonthlyLedgerResolver 클래스.
 * 트랜잭션 시점을 기준으로 알맞은 MonthlyAccountLedger(월별 계좌 원장)를 조회하거나,
 * 필요시 새로운 원장을 초기화하고 이전 달 장부를 이월(Carry-forward)하는 역할을 수행합니다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MonthlyLedgerResolver {
    private final MonthlyAccountLedgerRepository ledgerRepository;
    private final AccountRepository accountRepository;
    
    private static final DateTimeFormatter MONTH_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM");

    /**
     * 트랜잭션 발생 시간을 기준으로 해당 월의 MonthlyAccountLedger(월별 계좌 원장)를 찾아 반환합니다.
     * 원장이 존재하지 않을 경우, 새로운 트랜잭션 내에서 원장을 초기화한 뒤 다시 조회하여 반환합니다.
     *
     * @param accountId 계좌 ID
     * @param assetCode 자산 코드
     * @param assetType 자산 유형
     * @param transactedAt 트랜잭션 발생 시각
     * @return 해당하는 MonthlyAccountLedger(월별 계좌 원장)
     * @throws IllegalStateException 원장 초기화 후에도 장부를 불러오지 못한 경우
     */
    @Transactional
    public MonthlyAccountLedger resolveOrInitializeLedger(UUID accountId, String assetCode, AssetType assetType, OffsetDateTime transactedAt) {
        String targetMonth = transactedAt.format(MONTH_FORMATTER);

        // 현재 트랜잭션에서 대상 월의 원장(Ledger)을 우선 조회
        return ledgerRepository.findByAccountIdAndAssetCodeAndLedgerMonth(accountId, assetCode, targetMonth)
                .orElseGet(() -> {
                    // 원장이 없을 경우, 새로운 트랜잭션을 열어 이월 및 초기화 작업 수행
                    initializeInNewTransaction(accountId, assetCode, assetType, targetMonth);
                    
                    // 초기화 완료 후 다시 원장을 조회하여 반환
                    return ledgerRepository.findByAccountIdAndAssetCodeAndLedgerMonth(accountId, assetCode, targetMonth)
                            .orElseThrow(() -> new IllegalStateException("Failed to load ledger after initialization"));
                });
    }

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