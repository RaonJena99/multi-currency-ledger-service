package com.github.raonjena99.multi_currency_ledger_service.account.presentation;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.github.raonjena99.multi_currency_ledger_service.account.application.FxQuoteService;
import com.github.raonjena99.multi_currency_ledger_service.account.domain.FxQuote;
import com.github.raonjena99.multi_currency_ledger_service.common.security.AccountOwnershipGuard;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;

/**
 * 환전 견적 API 입니다. 계좌 소유자가 자기 계좌의 기준 통화와 외화 사이의 환전 견적을 받습니다.
 *
 * <p>견적은 잔고를 바꾸지 않습니다. 시세에 스프레드와 우대율을 적용한 환율과 받을 금액을 유효 시간 동안 고정할 뿐입니다.
 */
@Tag(name = "환전", description = "환전 견적. 계좌 소유자")
@RestController
@RequestMapping("/api/v1/accounts/{accountId}/fx-quotes")
@RequiredArgsConstructor
public class AccountFxController {

    private final FxQuoteService quoteService;
    private final AccountOwnershipGuard ownershipGuard;

    /**
     * 견적 요청 본문입니다.
     *
     * @param fromCurrency 내는 통화 (ISO 4217)
     * @param toCurrency   받는 통화 (ISO 4217)
     * @param amount       내는 금액. 내는 통화의 최소 단위보다 정밀하면 거부한다
     */
    public record FxQuoteRequest(
            @NotBlank @Size(max = 10) String fromCurrency,
            @NotBlank @Size(max = 10) String toCurrency,
            @NotNull @Positive @Digits(integer = 18, fraction = 8) BigDecimal amount) {
    }

    /**
     * 견적 응답입니다.
     *
     * @param quoteId          견적 ID. 체결 요청이 이 값을 보낸다
     * @param fromCurrency     내는 통화
     * @param fromAmount       내는 금액
     * @param toCurrency       받는 통화
     * @param toAmount         받을 금액. 받는 통화의 최소 단위로 내림한 값
     * @param marketRate       시세. 1 외화 = 기준 통화 얼마
     * @param customerRate     적용되는 환율. 1 외화 = 기준 통화 얼마
     * @param spreadRate       통화별 스프레드
     * @param preferentialRate 적용된 우대율
     * @param expiresAt        견적 만료 시각. 이후에는 이 견적으로 체결할 수 없다
     */
    public record FxQuoteResponse(
            UUID quoteId,
            String fromCurrency,
            BigDecimal fromAmount,
            String toCurrency,
            BigDecimal toAmount,
            BigDecimal marketRate,
            BigDecimal customerRate,
            BigDecimal spreadRate,
            BigDecimal preferentialRate,
            OffsetDateTime expiresAt) {

        static FxQuoteResponse from(FxQuote quote) {
            return new FxQuoteResponse(quote.quoteId(), quote.fromCurrency(), quote.fromAmount(),
                    quote.toCurrency(), quote.toAmount(), quote.marketRate(), quote.customerRate(),
                    quote.spreadRate(), quote.preferentialRate(), quote.expiresAt());
        }
    }

    @Operation(summary = "환전 견적",
            description = "계좌의 기준 통화와 설정된 외화 사이의 환전 견적을 냅니다. 환율은 시세에 스프레드를 붙이고 계좌의 "
                    + "우대율만큼 깎은 값입니다(외화를 살 때 비싸게, 팔 때 싸게). 받을 금액은 받는 통화의 최소 단위로 내림합니다. "
                    + "견적은 짧은 시간(기본 30초) 동안만 유효하며 잔고를 바꾸지 않습니다. 외화끼리의 환전은 기준 통화를 거쳐 두 번 합니다.")
    @ApiResponse(responseCode = "200", description = "성공")
    @ApiResponse(responseCode = "404", description = "계좌 없음 (ACCOUNT_NOT_FOUND)")
    @ApiResponse(responseCode = "422", description = "정지·해지된 계좌 (INVALID_ACCOUNT_STATE), 기준 통화가 끼지 않았거나 설정에 없는 통화 "
            + "(FX_PAIR_NOT_SUPPORTED), ISO 4217 이 아닌 통화 (UNSUPPORTED_ASSET), 통화별 한도를 벗어난 금액 (FX_AMOUNT_OUT_OF_RANGE)")
    @ApiResponse(responseCode = "503", description = "시세를 구할 수 없거나 낡음 (MARKET_DATA_UNAVAILABLE, EXCHANGE_RATE_UNAVAILABLE), "
            + "견적 저장소 장애 (FX_QUOTE_UNAVAILABLE). 잠시 뒤 다시 시도하면 됩니다")
    @PostMapping
    public ResponseEntity<FxQuoteResponse> quote(@PathVariable UUID accountId,
                                                 @Valid @RequestBody FxQuoteRequest request) {
        ownershipGuard.requireOwnership(accountId);

        FxQuote quote = quoteService.quote(accountId, request.fromCurrency(), request.toCurrency(), request.amount());
        return ResponseEntity.ok(FxQuoteResponse.from(quote));
    }
}
