package com.github.raonjena99.multi_currency_ledger_service.account.presentation;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.github.raonjena99.multi_currency_ledger_service.account.application.AccountTradeFacade;
import com.github.raonjena99.multi_currency_ledger_service.common.domain.Money;
import com.github.raonjena99.multi_currency_ledger_service.common.model.AssetType;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/accounts/{accountId}/trades")
@RequiredArgsConstructor
public class AccountTradeController {
    private final AccountTradeFacade accountTradeFacade;
    public record TradeRequestDto(
            String idempotencyKey,
            String targetAssetCode,
            AssetType targetAssetType,
            String paymentCurrency,
            java.math.BigDecimal quantity,
            java.math.BigDecimal unitPrice
    ) {}
    @PostMapping("/buy")
    public ResponseEntity<Void> buyAsset(
            @PathVariable UUID accountId,
            @RequestBody TradeRequestDto request) {
        
        accountTradeFacade.buyAsset(
                request.idempotencyKey(),
                accountId,
                request.targetAssetCode(),
                request.targetAssetType(),
                request.paymentCurrency(),
                Money.of(request.quantity().toPlainString(), request.targetAssetType(), request.targetAssetCode()),
                Money.of(request.unitPrice().toPlainString(), AssetType.FIAT, request.paymentCurrency())
        );
        
        return ResponseEntity.ok().build();
    }
    @PostMapping("/sell")
    public ResponseEntity<Void> sellAsset(
            @PathVariable UUID accountId,
            @RequestBody TradeRequestDto request) {
        
        accountTradeFacade.sellAsset(
                request.idempotencyKey(),
                accountId,
                request.targetAssetCode(),
                request.targetAssetType(),
                request.paymentCurrency(),
                Money.of(request.quantity().toPlainString(), request.targetAssetType(), request.targetAssetCode()),
                Money.of(request.unitPrice().toPlainString(), AssetType.FIAT, request.paymentCurrency())
        );
        
        return ResponseEntity.ok().build();
    }
}