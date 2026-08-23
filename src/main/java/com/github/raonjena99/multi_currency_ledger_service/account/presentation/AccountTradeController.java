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
            @jakarta.validation.constraints.NotBlank String idempotencyKey,
            @jakarta.validation.constraints.NotBlank String targetAssetCode,
            @jakarta.validation.constraints.NotNull AssetType targetAssetType,
            @jakarta.validation.constraints.NotBlank String paymentCurrency,
            @jakarta.validation.constraints.NotNull @jakarta.validation.constraints.Positive java.math.BigDecimal quantity,
            @jakarta.validation.constraints.NotNull @jakarta.validation.constraints.Positive java.math.BigDecimal unitPrice
    ) {}
    public record TradeResponseDto(UUID tradeId) {}

    @PostMapping("/buy")
    public ResponseEntity<TradeResponseDto> buyAsset(
            @PathVariable UUID accountId,
            @jakarta.validation.Valid @RequestBody TradeRequestDto request) {
        
        UUID tradeId = accountTradeFacade.buyAsset(
                request.idempotencyKey(),
                accountId,
                request.targetAssetCode(),
                request.targetAssetType(),
                request.paymentCurrency(),
                Money.of(request.quantity(), request.targetAssetType(), request.targetAssetCode()),
                request.unitPrice()
        );
        
        return ResponseEntity.ok(new TradeResponseDto(tradeId));
    }
    @PostMapping("/sell")
    public ResponseEntity<TradeResponseDto> sellAsset(
            @PathVariable UUID accountId,
            @jakarta.validation.Valid @RequestBody TradeRequestDto request) {
        
        UUID tradeId = accountTradeFacade.sellAsset(
                request.idempotencyKey(),
                accountId,
                request.targetAssetCode(),
                request.targetAssetType(),
                request.paymentCurrency(),
                Money.of(request.quantity(), request.targetAssetType(), request.targetAssetCode()),
                request.unitPrice()
        );
        
        return ResponseEntity.ok(new TradeResponseDto(tradeId));
    }
}