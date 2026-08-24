package com.github.raonjena99.multi_currency_ledger_service.account.presentation;

import java.math.BigDecimal;
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
import com.github.raonjena99.multi_currency_ledger_service.common.security.AccountOwnershipGuard;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/accounts/{accountId}/trades")
@RequiredArgsConstructor
public class AccountTradeController {

    private final AccountTradeFacade accountTradeFacade;
    private final AccountOwnershipGuard ownershipGuard;

    /**
     * 매수·매도 요청 본문입니다.
     *
     * @param idempotencyKey  중복 요청 방지 키
     * @param targetAssetCode 대상 자산 코드
     * @param targetAssetType 대상 자산 유형
     * @param paymentCurrency 결제 통화 코드
     * @param quantity        거래 수량
     * @param unitPrice       거래 단가
     */
    public record TradeRequestDto(
            // 서버가 (계좌 ID + 연산 종류) 접두어를 붙여 저장하므로, 저장 컬럼(255자) 안에
            // 안전히 들어가도록 클라이언트 키는 128자로 제한한다.
            @NotBlank @Size(max = 128) String idempotencyKey,
            @NotBlank @Size(max = 20) String targetAssetCode,
            @NotNull AssetType targetAssetType,
            @NotBlank @Size(max = 10) String paymentCurrency,
            @NotNull @Positive BigDecimal quantity,
            @NotNull @Positive BigDecimal unitPrice
    ) {
        public TradeRequestDto {
            // 자산/통화 코드를 정규화한다. 원장의 asset_code 는 입력 문자열을 그대로 저장하므로,
            // 정규화 없이 "btc" 와 "BTC" 가 들어오면 같은 자산이 서로 다른 원장 행으로 파편화되어
            // 잔고가 갈라진다(매수는 "btc" 행에, 매도는 "BTC" 행에서 잔고 부족).
            if (targetAssetCode != null) {
                targetAssetCode = targetAssetCode.trim().toUpperCase();
            }
            if (paymentCurrency != null) {
                paymentCurrency = paymentCurrency.trim().toUpperCase();
            }
        }
    }

    /** 거래 생성 응답입니다. */
    public record TradeResponseDto(UUID tradeId) {}

    @PostMapping("/buy")
    public ResponseEntity<TradeResponseDto> buyAsset(
            @PathVariable UUID accountId,
            @Valid @RequestBody TradeRequestDto request) {

        ownershipGuard.requireOwnership(accountId);

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
            @Valid @RequestBody TradeRequestDto request) {

        ownershipGuard.requireOwnership(accountId);

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
