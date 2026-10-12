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

import com.github.raonjena99.multi_currency_ledger_service.account.application.AccountFxConversionFacade;
import com.github.raonjena99.multi_currency_ledger_service.account.domain.FxConversion;
import com.github.raonjena99.multi_currency_ledger_service.common.security.AccountOwnershipGuard;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;

/**
 * 환전 체결 API 입니다. 계좌 소유자가 받아 둔 견적을 그 견적의 환율로 체결합니다.
 *
 * <p>환율은 견적이 정합니다. 고객은 단가를 보내지 않고, 체결 때 시세를 다시 조회하지도 않습니다.
 */
@Tag(name = "환전", description = "환전 체결. 계좌 소유자")
@RestController
@RequestMapping("/api/v1/accounts/{accountId}/fx-conversions")
@RequiredArgsConstructor
public class AccountFxConversionController {

    private final AccountFxConversionFacade conversionFacade;
    private final AccountOwnershipGuard ownershipGuard;

    /**
     * 체결 요청 본문입니다.
     *
     * @param quoteId        체결할 견적 ID. 견적 API 가 돌려준 값이다
     * @param idempotencyKey 중복 요청 방지 키. 같은 키로 다시 보내면 처음 체결을 돌려받는다
     */
    public record FxConversionRequest(
            @NotNull UUID quoteId,
            // 서버가 (계좌 ID + 연산 종류) 접두어를 붙여 저장하므로, 저장 컬럼(255자) 안에 안전히 들어가도록 128자로 제한한다.
            @NotBlank @Size(max = 128) String idempotencyKey) {
    }

    /**
     * 체결 응답입니다. 회사 쪽 금액(시세 가치, 스프레드 수익)은 내부 정보라 싣지 않습니다.
     *
     * @param conversionId 체결 ID. 원장 거래 ID 와 같다
     * @param quoteId      체결한 견적 ID
     * @param fromCurrency 낸 통화
     * @param fromAmount   낸 금액
     * @param toCurrency   받은 통화
     * @param toAmount     받은 금액
     * @param customerRate 적용된 환율. 1 외화 = 기준 통화 얼마
     * @param convertedAt  체결 시각
     */
    public record FxConversionResponse(
            UUID conversionId,
            UUID quoteId,
            String fromCurrency,
            BigDecimal fromAmount,
            String toCurrency,
            BigDecimal toAmount,
            BigDecimal customerRate,
            OffsetDateTime convertedAt) {

        static FxConversionResponse from(FxConversion conversion) {
            return new FxConversionResponse(conversion.getId(), conversion.getQuoteId(), conversion.getFromCurrency(),
                    conversion.getFromAmount(), conversion.getToCurrency(), conversion.getToAmount(),
                    conversion.getCustomerRate(), conversion.getConvertedAt());
        }
    }

    @Operation(summary = "환전 체결",
            description = "받아 둔 견적을 견적의 환율로 체결합니다. 견적 하나는 한 번만 체결할 수 있고, 체결가는 견적에 고정되어 "
                    + "시세를 다시 조회하지 않습니다. 같은 멱등성 키로 다시 보내면 처음 체결을 돌려줍니다(견적이 만료된 뒤에도). "
                    + "외화를 팔면 평균 단가 대비 실현 환차손익이 고객 계정의 별도 분개로 기록됩니다.")
    @ApiResponse(responseCode = "200", description = "성공")
    @ApiResponse(responseCode = "404", description = "계좌 없음 (ACCOUNT_NOT_FOUND)")
    @ApiResponse(responseCode = "409", description = "잔고 부족 (INSUFFICIENT_BALANCE), 이미 체결된 견적 (FX_QUOTE_ALREADY_USED), "
            + "같은 멱등성 키로 처리 중인 요청 (DUPLICATE_REQUEST), 동시 수정 충돌 (CONCURRENCY_CONFLICT). 뒤의 둘은 같은 멱등성 키로 "
            + "잠시 뒤 재시도하면 됩니다")
    @ApiResponse(responseCode = "422", description = "견적이 없거나 만료되었거나 이 계좌의 견적이 아님 (FX_QUOTE_EXPIRED, 새 견적을 받아 "
            + "다시 시도), 정지·해지된 계좌 (INVALID_ACCOUNT_STATE)")
    @ApiResponse(responseCode = "503", description = "견적 저장소 장애 (FX_QUOTE_UNAVAILABLE). 잠시 뒤 같은 요청을 다시 보내면 됩니다")
    @PostMapping
    public ResponseEntity<FxConversionResponse> convert(@PathVariable UUID accountId,
                                                        @Valid @RequestBody FxConversionRequest request) {
        ownershipGuard.requireOwnership(accountId);

        FxConversion conversion = conversionFacade.convert(accountId, request.quoteId(), request.idempotencyKey());
        return ResponseEntity.ok(FxConversionResponse.from(conversion));
    }
}
