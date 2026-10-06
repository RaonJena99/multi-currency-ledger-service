package com.github.raonjena99.multi_currency_ledger_service.transaction.presentation;

import java.time.YearMonth;
import java.time.format.DateTimeParseException;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.github.raonjena99.multi_currency_ledger_service.transaction.application.LedgerQueryService;
import com.github.raonjena99.multi_currency_ledger_service.transaction.application.LedgerQueryService.TrialBalance;

import lombok.RequiredArgsConstructor;

/**
 * 관리자가 월별 시산표를 조회하는 REST API 컨트롤러입니다.
 */
@RestController
@RequestMapping("/api/v1/admin/ledger/trial-balance")
@RequiredArgsConstructor
public class LedgerTrialBalanceController {

    private final LedgerQueryService queryService;

    /**
     * 한 달 동안의 분개를 기준 통화별로 집계해 차대가 맞는지 보여줍니다.
     *
     * @param month 조회할 월 (yyyy-MM, UTC 기준)
     */
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
