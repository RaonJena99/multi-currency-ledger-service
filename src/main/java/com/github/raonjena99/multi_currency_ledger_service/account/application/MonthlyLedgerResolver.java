package com.github.raonjena99.multi_currency_ledger_service.account.application;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.github.raonjena99.multi_currency_ledger_service.account.domain.MonthlyAccountLedger;
import com.github.raonjena99.multi_currency_ledger_service.account.infrastructure.MonthlyAccountLedgerRepository;
import com.github.raonjena99.multi_currency_ledger_service.common.model.AssetType;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class MonthlyLedgerResolver {
    private final MonthlyAccountLedgerRepository ledgerRepository;
    private static final DateTimeFormatter MONTH_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM");

    /*
     * 트랜잭션 시점을 기준으로 알맞은 월차 원장을 찾아 반환
     */
    @Transactional
    public MonthlyAccountLedger resolveOrInitializeLedger(UUID accountId, String assetCode, AssetType assetType, OffsetDateTime transactedAt) {
        String targetMonth = transactedAt.format(MONTH_FORMATTER);

        // 현재 트랜잭션에서 먼저 조회
        return ledgerRepository.findByAccountIdAndAssetCodeAndLedgerMonth(accountId, assetCode, targetMonth)
                .orElseGet(() -> {
                    initializeInNewTransaction(accountId, assetCode, assetType, targetMonth);
                    
                    return ledgerRepository.findByAccountIdAndAssetCodeAndLedgerMonth(accountId, assetCode, targetMonth)
                            .orElseThrow(() -> new IllegalStateException("Failed to load ledger after initialization"));
                });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void initializeInNewTransaction(UUID accountId, String assetCode, AssetType assetType, String targetMonth) {
        try {
            if (ledgerRepository.findByAccountIdAndAssetCodeAndLedgerMonth(accountId, assetCode, targetMonth).isPresent()) {
                return; 
            }

            ledgerRepository.findFirstByAccountIdAndAssetCodeOrderByLedgerMonthDesc(accountId, assetCode)
                .ifPresentOrElse(
                    // 전월 장부가 있을 경우
                    prevLedger -> {
                        MonthlyAccountLedger rolledOver = MonthlyAccountLedger.carryForwardFrom(prevLedger, targetMonth);
                        ledgerRepository.save(rolledOver); // flush 없이 순수 save
                    },
                    // 전월 장부가 없는 경우
                    () -> {
                        MonthlyAccountLedger newLedger = new MonthlyAccountLedger(accountId, assetCode, assetType, targetMonth);
                        ledgerRepository.save(newLedger); // flush 없이 순수 save
                    }
                );
        } catch (DataIntegrityViolationException e) {
            log.debug("Ledger uniquely initialized by another thread for {} - {}", accountId, assetCode);
        }
    }
}