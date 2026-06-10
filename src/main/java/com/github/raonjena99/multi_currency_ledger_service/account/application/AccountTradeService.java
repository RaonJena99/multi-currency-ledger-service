package com.github.raonjena99.multi_currency_ledger_service.account.application;

import java.math.BigDecimal;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.github.raonjena99.multi_currency_ledger_service.account.domain.AccountBalance;
import com.github.raonjena99.multi_currency_ledger_service.account.domain.event.TradeExecutedEvent;
import com.github.raonjena99.multi_currency_ledger_service.account.infrastructure.AccountBalanceRepository;
import com.github.raonjena99.multi_currency_ledger_service.common.domain.Money;
import com.github.raonjena99.multi_currency_ledger_service.common.model.AssetType;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;


/**
 * 오직 AccountBalance 애그리거트만 수정 
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccountTradeService {
    private final AccountBalanceRepository accountBalanceRepository;
    private final ApplicationEventPublisher eventPublisher;

    private static final String FIAT_CODE = "KRW";

    @Transactional
    public UUID buyAsset(UUID accountId, String targetAssetCode, AssetType targetAssetType, 
                        Money buyQuantity, Money unitPrice) {
        
        BigDecimal exchangeRate = BigDecimal.ONE;

        AccountBalance targetAssetBalance = getOrCreateBalance(accountId, targetAssetCode, targetAssetType);
        AccountBalance fiatBalance = getOrCreateBalance(accountId, FIAT_CODE, AssetType.FIAT);

        Money requiredFiatAmount = unitPrice.multiply(buyQuantity.getAmount()); 

        fiatBalance.subtractBalance(requiredFiatAmount);
        targetAssetBalance.addBalance(buyQuantity, unitPrice);

        UUID tradeId = UUID.randomUUID();

        TradeExecutedEvent event = new TradeExecutedEvent(
            tradeId, accountId, targetAssetCode, targetAssetType.name(), FIAT_CODE, "BUY", 
            buyQuantity.getAmount(), unitPrice.getAmount(), exchangeRate, BigDecimal.ZERO
        );
        eventPublisher.publishEvent(event);
        
        log.info("Account balance updated for BUY. TradeID: {}", tradeId);
        return tradeId;
    }

    @Transactional
    public UUID sellAsset(UUID accountId, String targetAssetCode, AssetType targetAssetType, 
                        Money sellQuantity, Money sellUnitPrice) {
        
        BigDecimal exchangeRate = BigDecimal.ONE;
        
        AccountBalance targetAssetBalance = getOrCreateBalance(accountId, targetAssetCode, targetAssetType);
        AccountBalance fiatBalance = getOrCreateBalance(accountId, FIAT_CODE, AssetType.FIAT);

        Money averageCost = targetAssetBalance.subtractBalance(sellQuantity);
        Money earnedFiatAmount = Money.of(
            sellUnitPrice.multiply(sellQuantity.getAmount()).getAmount().toPlainString(),
            AssetType.FIAT
        );
        fiatBalance.addBalance(earnedFiatAmount, Money.of("1", AssetType.FIAT));

        UUID tradeId = UUID.randomUUID();

        TradeExecutedEvent event = new TradeExecutedEvent(
            tradeId, accountId, targetAssetCode, targetAssetType.name(), FIAT_CODE, "SELL", 
            sellQuantity.getAmount(), sellUnitPrice.getAmount(), exchangeRate, averageCost.getAmount()
        );
        eventPublisher.publishEvent(event);
        
        log.info("Account balance updated for SELL. TradeID: {}", tradeId);
        return tradeId;
    }

    private AccountBalance getOrCreateBalance(UUID accountId, String assetCode, AssetType assetType) {
        return accountBalanceRepository.findByAccountIdAndAssetCode(accountId, assetCode)
                .orElseGet(() -> {
                    AccountBalance newBalance = new AccountBalance(accountId, assetCode, assetType);
                    return accountBalanceRepository.save(newBalance);
                });
    }

    
}
