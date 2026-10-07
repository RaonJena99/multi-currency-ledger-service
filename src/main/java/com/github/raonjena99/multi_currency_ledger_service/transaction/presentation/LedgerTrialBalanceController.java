package com.github.raonjena99.multi_currency_ledger_service.transaction.presentation;

import java.time.YearMonth;
import java.time.format.DateTimeParseException;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import com.github.raonjena99.multi_currency_ledger_service.transaction.application.LedgerQueryService;
import com.github.raonjena99.multi_currency_ledger_service.transaction.application.LedgerQueryService.TrialBalance;

import lombok.RequiredArgsConstructor;

/**
 * 관리자가 월별 시산표를 조회하는 REST API 컨트롤러입니다.
 */
@Tag(name = "원장 운영 (관리자)", description = "시산표·정합성 점검·원장 데드레터")
@RestController
@RequestMapping("/api/v1/admin/ledger/trial-balance")
@RequiredArgsConstructor
public class LedgerTrialBalanceController {

    private final LedgerQueryService queryService;

    /**
     * 한 달 동안의 분개를 기준 통화별로 집계해 차대가 맞는지 보여줍니다.
     *
     * <p>실현 손익은 고객 계정의 별도 분개(이익은 대변, 손실은 차변)라 차변·대변 합계에 이미 들어 있고, 차대는
     * {@code 차변 = 대변} 입니다. {@code realizedPnlTotal} 은 그 손익 분개의 순합(이익 − 손실)을 보여 주는
     * 참고값입니다.
     *
     * @param month 조회할 월 (yyyy-MM, UTC 기준)
     */
    @Operation(summary = "월 시산표 (기준 통화별 차변 = 대변)",
            description = "실현 손익은 고객 계정의 별도 분개(REALIZED_PNL_*)라 합계에 들어 있습니다. "
                    + "realizedPnlTotal 은 그 분개의 순합(이익 − 손실)으로 대차 판정에는 쓰지 않는 참고값입니다.")
    @GetMapping
    public ResponseEntity<TrialBalance> trialBalance(@RequestParam("month") String month) {
        YearMonth parsed;
        try {
            parsed = YearMonth.parse(month);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("month 는 yyyy-MM 형식이어야 합니다: " + month);
        }
        return ResponseEntity.ok(queryService.trialBalance(parsed));
    }
}
