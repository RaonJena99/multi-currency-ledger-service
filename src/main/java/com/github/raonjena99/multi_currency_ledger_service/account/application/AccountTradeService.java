package com.github.raonjena99.multi_currency_ledger_service.account.application;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.github.raonjena99.multi_currency_ledger_service.account.domain.MonthlyAccountLedger;
import com.github.raonjena99.multi_currency_ledger_service.account.domain.event.TradeExecutedEvent;
import com.github.raonjena99.multi_currency_ledger_service.common.domain.Money;
import com.github.raonjena99.multi_currency_ledger_service.common.model.AssetType;
import com.github.raonjena99.multi_currency_ledger_service.common.port.ExchangeRateProvider;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class AccountTradeService {
    
    private final MonthlyLedgerResolver ledgerResolver; 
    private final ApplicationEventPublisher eventPublisher;
    private final ExchangeRateProvider exchangeRateProvider;

    private static final String FIAT_CODE = "KRW";

    @Transactional
    public UUID buyAsset(UUID accountId, String targetAssetCode, AssetType targetAssetType, 
                        Money buyQuantity, Money unitPrice) {
        
        BigDecimal exchangeRate = BigDecimal.ONE;
        OffsetDateTime transactedAt = OffsetDateTime.now();

        MonthlyAccountLedger targetAssetLedger = ledgerResolver.resolveOrInitializeLedger(accountId, targetAssetCode, targetAssetType, transactedAt);
        MonthlyAccountLedger fiatLedger = ledgerResolver.resolveOrInitializeLedger(accountId, FIAT_CODE, AssetType.FIAT, transactedAt);

        Money requiredFiatAmount = unitPrice.multiply(buyQuantity.getAmount()); 

        // Version 낙관적 락 작동
        fiatLedger.subtractBalance(requiredFiatAmount);
        targetAssetLedger.addBalance(buyQuantity, unitPrice);

        UUID tradeId = UUID.randomUUID();

        var rateInfo = exchangeRateProvider.getExchangeRate(targetAssetCode, FIAT_CODE);

        TradeExecutedEvent event = new TradeExecutedEvent(
            tradeId, accountId, targetAssetCode, targetAssetType.name(), FIAT_CODE, "BUY", 
            buyQuantity.getAmount(), unitPrice.getAmount(), exchangeRate, BigDecimal.ZERO,
            rateInfo.isStale()
        );
        eventPublisher.publishEvent(event);
        
        log.info("Monthly Ledger updated for BUY. TradeID: {}", tradeId);
        return tradeId;
    }

    @Transactional
    public UUID sellAsset(UUID accountId, String targetAssetCode, AssetType targetAssetType, 
                        Money sellQuantity, Money sellUnitPrice) {
        
        BigDecimal exchangeRate = BigDecimal.ONE;
        OffsetDateTime transactedAt = OffsetDateTime.now();
        
        MonthlyAccountLedger targetAssetLedger = ledgerResolver.resolveOrInitializeLedger(accountId, targetAssetCode, targetAssetType, transactedAt);
        MonthlyAccountLedger fiatLedger = ledgerResolver.resolveOrInitializeLedger(accountId, FIAT_CODE, AssetType.FIAT, transactedAt);

        Money averageCost = targetAssetLedger.subtractBalance(sellQuantity);
        Money earnedFiatAmount = Money.of(
            sellUnitPrice.multiply(sellQuantity.getAmount()).getAmount().toPlainString(),
            AssetType.FIAT
        );
        fiatLedger.addBalance(earnedFiatAmount, Money.of("1", AssetType.FIAT));

        UUID tradeId = UUID.randomUUID();

        var rateInfo = exchangeRateProvider.getExchangeRate(targetAssetCode, FIAT_CODE);

        TradeExecutedEvent event = new TradeExecutedEvent(
            tradeId, accountId, targetAssetCode, targetAssetType.name(), FIAT_CODE, "SELL", 
            sellQuantity.getAmount(), sellUnitPrice.getAmount(), rateInfo.rate(), averageCost.getAmount(),
            rateInfo.isStale()
        );

        eventPublisher.publishEvent(event);
        
        log.info("Monthly Ledger updated for SELL. TradeID: {}", tradeId);
        return tradeId;
    }
}