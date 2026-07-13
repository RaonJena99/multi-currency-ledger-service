package com.github.raonjena99.multi_currency_ledger_service.account.application;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.github.raonjena99.multi_currency_ledger_service.account.domain.MonthlyAccountLedger;
import com.github.raonjena99.multi_currency_ledger_service.common.domain.Money;
import com.github.raonjena99.multi_currency_ledger_service.common.model.AssetType;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class AccountTradeFacade {

    private final MonthlyLedgerResolver ledgerResolver;
    private final AccountTradeService tradeService;

    /**
     * @Transactional 이 없는 Facade 계층
     */
    public UUID buyAsset(String idempotencyKey, UUID accountId, String targetAssetCode, AssetType targetAssetType, 
                         String paymentCurrency, Money buyQuantity, Money unitPrice) {
        
        // 트랜잭션 진입 전 현재 시각 기록
        OffsetDateTime transactedAt = OffsetDateTime.now();
        
        // 1. 트랜잭션 외부에서 원장 초기화를 수행합니다. (커넥션 풀 데드락 방지)
        MonthlyAccountLedger targetAssetLedger = ledgerResolver.resolveOrInitializeLedger(
                accountId, targetAssetCode, targetAssetType, transactedAt);
                
        MonthlyAccountLedger fiatLedger = ledgerResolver.resolveOrInitializeLedger(
                accountId, paymentCurrency, AssetType.FIAT, transactedAt);

        // 2. 초기화된 원장 객체를 파라미터로 넘겨주며 실제 본 거래 로직을 실행합니다.
        return tradeService.executeBuyAsset(idempotencyKey, accountId, targetAssetCode, targetAssetType, 
                                            paymentCurrency, buyQuantity, unitPrice, 
                                            targetAssetLedger, fiatLedger);
    }

    public UUID sellAsset(String idempotencyKey, 
                          UUID accountId, String targetAssetCode, AssetType targetAssetType, 
                          String paymentCurrency, Money sellQuantity, Money sellUnitPrice) {
        
        OffsetDateTime transactedAt = OffsetDateTime.now();
        
        // 1. 트랜잭션 외부에서 원장 초기화를 수행
        MonthlyAccountLedger targetAssetLedger = ledgerResolver.resolveOrInitializeLedger(
                accountId, targetAssetCode, targetAssetType, transactedAt);
                
        MonthlyAccountLedger fiatLedger = ledgerResolver.resolveOrInitializeLedger(
                accountId, paymentCurrency, AssetType.FIAT, transactedAt);

        // 2. 본 거래 로직 실행
        return tradeService.executeSellAsset(idempotencyKey, accountId, targetAssetCode, targetAssetType, 
                                             paymentCurrency, sellQuantity, sellUnitPrice, 
                                             targetAssetLedger, fiatLedger);
    }
}