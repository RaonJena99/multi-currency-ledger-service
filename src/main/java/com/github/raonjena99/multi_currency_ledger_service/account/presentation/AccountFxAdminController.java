package com.github.raonjena99.multi_currency_ledger_service.account.presentation;

import java.math.BigDecimal;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.github.raonjena99.multi_currency_ledger_service.account.application.AccountFxTermsService;
import com.github.raonjena99.multi_currency_ledger_service.account.application.AccountFxTermsService.PreferentialRate;
import com.github.raonjena99.multi_currency_ledger_service.common.security.LedgerPrincipal;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;

/**
 * 계좌별 환전 우대율을 조회하고 지정하는 관리자 API 입니다.
 *
 * <p>우대율은 환전 스프레드를 얼마나 깎아 줄지를 정합니다(0.9 = 스프레드의 90% 를 깎음). 회사의 수익을 직접 깎는
 * 값이라 관리자만 바꿀 수 있습니다.
 */
@Tag(name = "환전 (관리자)", description = "계좌별 환전 우대율. 관리자 전용")
@RestController
@RequestMapping("/api/v1/admin/accounts/{accountId}/fx-preferential-rate")
@RequiredArgsConstructor
public class AccountFxAdminController {

    private final AccountFxTermsService termsService;

    /**
     * 우대율 지정 요청 본문입니다.
     *
     * @param rate 우대율. 0 이상 1 미만, 소수 4자리까지. 설정의 상한(기본 0.9)을 넘을 수 없다
     */
    public record PreferentialRateRequest(
            @NotNull @DecimalMin("0") @DecimalMax(value = "1", inclusive = false)
            @Digits(integer = 1, fraction = 4) BigDecimal rate) {
    }

    /**
     * 우대율 응답입니다.
     *
     * @param rate     실제로 적용되는 우대율
     * @param explicit 계좌에 지정한 값이면 true, 지정하지 않아 기본 우대율을 쓰면 false
     */
    public record PreferentialRateResponse(BigDecimal rate, boolean explicit) {
        static PreferentialRateResponse from(PreferentialRate view) {
            return new PreferentialRateResponse(view.rate(), view.explicit());
        }
    }

    @Operation(summary = "계좌의 환전 우대율 조회",
            description = "지정하지 않은 계좌는 설정의 기본 우대율을 돌려주고 explicit 이 false 입니다.")
    @ApiResponse(responseCode = "200", description = "성공")
    @ApiResponse(responseCode = "404", description = "계좌 없음 (ACCOUNT_NOT_FOUND)")
    @GetMapping
    public ResponseEntity<PreferentialRateResponse> get(@PathVariable UUID accountId) {
        return ResponseEntity.ok(PreferentialRateResponse.from(termsService.preferentialRateOf(accountId)));
    }

    @Operation(summary = "계좌의 환전 우대율 지정",
            description = "0 이상 1 미만, 소수 4자리까지입니다. 설정의 상한(ledger.fx.max-preferential-rate, 기본 0.9)을 넘을 수 없습니다. "
                    + "0 은 우대 없음을 명시한 값입니다. 이미 받은 견적에는 영향이 없고 다음 견적부터 적용됩니다.")
    @ApiResponse(responseCode = "200", description = "성공")
    @ApiResponse(responseCode = "400", description = "범위를 벗어난 값, 상한 초과, 소수 4자리 초과 (INVALID_INPUT)")
    @ApiResponse(responseCode = "404", description = "계좌 없음 (ACCOUNT_NOT_FOUND)")
    @PutMapping
    public ResponseEntity<PreferentialRateResponse> change(@PathVariable UUID accountId,
                                                           @Valid @RequestBody PreferentialRateRequest request) {
        PreferentialRate changed = termsService.changePreferentialRate(accountId, request.rate(), currentSubject());
        return ResponseEntity.ok(PreferentialRateResponse.from(changed));
    }

    /** 로그에 남길 처리자. 게이트웨이가 넣어 준 주체 식별자(X-Auth-Subject)입니다. */
    private static String currentSubject() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getPrincipal() instanceof LedgerPrincipal principal
                ? principal.subject()
                : "unknown";
    }
}
