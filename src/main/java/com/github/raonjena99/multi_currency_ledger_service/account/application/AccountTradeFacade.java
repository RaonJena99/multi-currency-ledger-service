package com.github.raonjena99.multi_currency_ledger_service.account.application;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.github.raonjena99.multi_currency_ledger_service.account.domain.Account;
import com.github.raonjena99.multi_currency_ledger_service.account.infrastructure.AccountRepository;
import com.github.raonjena99.multi_currency_ledger_service.common.domain.Money;
import com.github.raonjena99.multi_currency_ledger_service.common.exception.InvalidAccountStateException;
import com.github.raonjena99.multi_currency_ledger_service.common.model.AssetType;
import com.github.raonjena99.multi_currency_ledger_service.common.port.ExchangeRateProvider;

import lombok.RequiredArgsConstructor;

/**
 * 이 클래스는 계좌의 자산 매수 및 매도 거래를 처리하는 Facade(중재자) 역할을 수행합니다.
 */
@Component
@RequiredArgsConstructor
public class AccountTradeFacade {

    private final MonthlyLedgerResolver ledgerResolver;
    private final AccountTradeService tradeService;
    private final AccountRepository accountRepository;
    private final ExchangeRateProvider exchangeRateProvider;

    /**
     * @Transactional 이 없는 Facade 계층
     */
    public UUID buyAsset(String idempotencyKey, UUID accountId, String targetAssetCode, AssetType targetAssetType, 
                         String paymentCurrency, Money buyQuantity, Money unitPrice) {
        
        // 트랜잭션 진입 전 현재 시각 기록
        OffsetDateTime transactedAt = OffsetDateTime.now();
        
        // 1. 트랜잭션 외부에서 원장 존재 여부 보장 (커넥션 풀 데드락 방지)
        ledgerResolver.resolveOrInitializeLedger(accountId, targetAssetCode, targetAssetType, transactedAt);
        ledgerResolver.resolveOrInitializeLedger(accountId, paymentCurrency, AssetType.FIAT, transactedAt);

        // 2. 외부 API를 찌르는 통신을 DB 트랜잭션 밖에서 수행 (Connection Pool 고갈 방지)
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new IllegalArgumentException("Account not found: " + accountId));
                
        if (!account.isActive()) {
            throw new InvalidAccountStateException("Account is not active for trading: " + accountId);
        }
        
        String baseCurrency = account.getBaseCurrency();
        
        var targetRateInfo = exchangeRateProvider.getExchangeRate(targetAssetCode, paymentCurrency);
        java.math.BigDecimal fiatToBaseRate = null;
        if (!paymentCurrency.equals(baseCurrency)) {
            fiatToBaseRate = exchangeRateProvider.getExchangeRate(paymentCurrency, baseCurrency).rate();
        }

        // 3. transactedAt 및 환율 데이터를 넘겨서 호출 (Service는 순수하게 DB 연산에만 집중)
        return tradeService.executeBuyAsset(idempotencyKey, accountId, targetAssetCode, targetAssetType, 
                                        paymentCurrency, buyQuantity, unitPrice, transactedAt,
                                        targetRateInfo.rate(), targetRateInfo.isStale(), fiatToBaseRate);
    }

    public UUID sellAsset(String idempotencyKey, 
                          UUID accountId, String targetAssetCode, AssetType targetAssetType, 
                          String paymentCurrency, Money sellQuantity, Money sellUnitPrice) {
        
        OffsetDateTime transactedAt = OffsetDateTime.now();
        
        // 1. 트랜잭션 외부에서 원장 존재 여부 보장 (커넥션 풀 데드락 방지)
        ledgerResolver.resolveOrInitializeLedger(accountId, targetAssetCode, targetAssetType, transactedAt);
        ledgerResolver.resolveOrInitializeLedger(accountId, paymentCurrency, AssetType.FIAT, transactedAt);

        // 2. 외부 API를 찌르는 통신을 DB 트랜잭션 밖에서 수행 (Connection Pool 고갈 방지)
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new IllegalArgumentException("Account not found: " + accountId));
                
        if (!account.isActive()) {
            throw new InvalidAccountStateException("Account is not active for trading: " + accountId);
        }
        
        String baseCurrency = account.getBaseCurrency();
        
        var targetRateInfo = exchangeRateProvider.getExchangeRate(targetAssetCode, paymentCurrency);
        java.math.BigDecimal fiatToBaseRate = null;
        if (!paymentCurrency.equals(baseCurrency)) {
            fiatToBaseRate = exchangeRateProvider.getExchangeRate(paymentCurrency, baseCurrency).rate();
        }

        // 3. transactedAt 및 환율 데이터를 넘겨서 호출 (Service는 순수하게 DB 연산에만 집중)
        return tradeService.executeSellAsset(idempotencyKey, accountId, targetAssetCode, targetAssetType, 
                                             paymentCurrency, sellQuantity, sellUnitPrice, transactedAt,
                                             targetRateInfo.rate(), targetRateInfo.isStale(), fiatToBaseRate); 
    }
}