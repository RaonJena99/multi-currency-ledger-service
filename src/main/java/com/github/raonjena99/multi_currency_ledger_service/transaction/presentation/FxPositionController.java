package com.github.raonjena99.multi_currency_ledger_service.transaction.presentation;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.github.raonjena99.multi_currency_ledger_service.transaction.application.FxPositionService;
import com.github.raonjena99.multi_currency_ledger_service.transaction.application.FxPositionService.FxPositionReport;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

/**
 * 관리자가 회사의 외화 포지션을 조회하는 REST API 컨트롤러입니다.
 *
 * <p>환전이 일어나면 회사는 고객의 반대편에서 외화 포지션을 갖습니다. 이 조회는 그 포지션과 현재 시세 재평가를 보여 주며
 * 원장에는 아무것도 기록하지 않습니다.
 */
@Tag(name = "원장 운영 (관리자)", description = "시산표·정합성 점검·원장 데드레터·회사 외화 포지션")
@RestController
@RequestMapping("/api/v1/admin/ledger/fx-positions")
@RequiredArgsConstructor
public class FxPositionController {

    private final FxPositionService positionService;

    @Operation(summary = "회사 외화 포지션과 재평가",
            description = "환전으로 쌓인 회사 FX 계정의 통화별 순수량(음수는 회사가 판 포지션)과 장부가를 현재 시세로 재평가합니다. "
                    + "고객 계좌의 기준 통화별로 장부를 나누고(금액은 그 기준 통화), 통화별 순수량은 모든 장부를 합산해 exposures 로 "
                    + "따로 보여 줍니다. 합산 손익(totalResult) = 스프레드 수익(spreadRevenue) + 환손익(fxResult) = 포지션 시장가치의 "
                    + "합입니다. 환손익은 실현과 미실현을 나누지 않습니다(닫힌 포지션의 손익도 장부가에 남아 현재 시세 기준으로 "
                    + "합산됩니다). 시세를 구하지 못한 통화는 marketRate·marketValue·fxResult 가 null 이고 그 장부의 합산 손익도 "
                    + "null 입니다. 낡은 시세는 stale 로 표시해 보여 줍니다. 원장에는 아무것도 기록하지 않는 조회입니다.")
    @ApiResponse(responseCode = "200", description = "성공. 환전이 없으면 books 와 exposures 가 비어 있습니다")
    @GetMapping
    public ResponseEntity<FxPositionReport> fxPositions() {
        return ResponseEntity.ok(positionService.report());
    }
}
