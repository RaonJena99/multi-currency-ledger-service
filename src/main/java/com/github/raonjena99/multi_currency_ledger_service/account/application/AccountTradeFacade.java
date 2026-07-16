package com.github.raonjena99.multi_currency_ledger_service.account.application;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.github.raonjena99.multi_currency_ledger_service.common.domain.Money;
import com.github.raonjena99.multi_currency_ledger_service.common.model.AssetType;

import lombok.RequiredArgsConstructor;

/**
 * 이 클래스는 계좌의 자산 매수 및 매도 거래를 처리하는 Facade(중재자) 역할을 수행합니다.
 */
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
        
        // 1. 트랜잭션 외부에서 원장 존재 여부 보장 (커넥션 풀 데드락 방지)
        ledgerResolver.resolveOrInitializeLedger(accountId, targetAssetCode, targetAssetType, transactedAt);
        ledgerResolver.resolveOrInitializeLedger(accountId, paymentCurrency, AssetType.FIAT, transactedAt);

        // 2. transactedAt을 넘겨서 호출
        return tradeService.executeBuyAsset(idempotencyKey, accountId, targetAssetCode, targetAssetType, 
                                        paymentCurrency, buyQuantity, unitPrice, transactedAt);
    }

    public UUID sellAsset(String idempotencyKey, 
                          UUID accountId, String targetAssetCode, AssetType targetAssetType, 
                          String paymentCurrency, Money sellQuantity, Money sellUnitPrice) {
        
        OffsetDateTime transactedAt = OffsetDateTime.now();
        
        // 1. 트랜잭션 외부에서 원장 존재 여부 보장 (커넥션 풀 데드락 방지)
        ledgerResolver.resolveOrInitializeLedger(accountId, targetAssetCode, targetAssetType, transactedAt);
        ledgerResolver.resolveOrInitializeLedger(accountId, paymentCurrency, AssetType.FIAT, transactedAt);

        // 2. transactedAt을 넘겨서 호출
        return tradeService.executeSellAsset(idempotencyKey, accountId, targetAssetCode, targetAssetType, 
                                             paymentCurrency, sellQuantity, sellUnitPrice,transactedAt); 
    }
}