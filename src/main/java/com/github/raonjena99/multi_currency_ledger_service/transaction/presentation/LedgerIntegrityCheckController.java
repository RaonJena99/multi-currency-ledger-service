package com.github.raonjena99.multi_currency_ledger_service.transaction.presentation;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import com.github.raonjena99.multi_currency_ledger_service.transaction.application.LedgerIntegrityCheckService;
import com.github.raonjena99.multi_currency_ledger_service.transaction.application.LedgerIntegrityCheckService.IntegrityCheckResult;

import lombok.RequiredArgsConstructor;

/**
 * 관리자가 원장 정합성 점검을 실행하고 결과를 조회하는 REST API 컨트롤러입니다.
 *
 * <p>점검은 매일 스케줄로도 실행됩니다. 원장 DLT 재처리나 아웃박스 재발행으로 복구한 뒤,
 * 다음 스케줄을 기다리지 않고 바로 확인하는 용도로 수동 실행을 둡니다.
 */
@Tag(name = "원장 운영 (관리자)", description = "시산표·정합성 점검·원장 데드레터")
@RestController
@RequestMapping("/api/v1/admin/ledger/integrity-checks")
@RequiredArgsConstructor
public class LedgerIntegrityCheckController {

    private final LedgerIntegrityCheckService checkService;

    @Operation(summary = "정합성 점검 즉시 실행")
    @PostMapping
    public ResponseEntity<IntegrityCheckResult> runCheck() {
        return ResponseEntity.ok(checkService.runCheck());
    }

    /**
     * 가장 최근 점검 결과를 조회합니다. 아직 점검한 적이 없으면 204 입니다.
     */
    @Operation(summary = "최근 정합성 점검 결과")
    @ApiResponse(responseCode = "200", description = "성공")
    @ApiResponse(responseCode = "204", description = "아직 점검한 적이 없음")
    @GetMapping("/latest")
    public ResponseEntity<IntegrityCheckResult> latest() {
        return checkService.findLatest()
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }
}
