package com.github.raonjena99.multi_currency_ledger_service.transaction.presentation;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.github.raonjena99.multi_currency_ledger_service.common.security.AccountOwnershipGuard;
import com.github.raonjena99.multi_currency_ledger_service.transaction.application.LedgerQueryService;
import com.github.raonjena99.multi_currency_ledger_service.transaction.application.LedgerQueryService.TransactionDetail;
import com.github.raonjena99.multi_currency_ledger_service.transaction.application.LedgerQueryService.TransactionPage;

import lombok.RequiredArgsConstructor;

/**
 * 계좌의 거래 내역과 거래별 분개를 조회하는 REST API 컨트롤러입니다.
 *
 * <p>분개는 Kafka 를 거쳐 비동기로 기록되므로, 거래 직후에는 아직 조회되지 않을 수 있습니다(상세는 404).
 */
@RestController
@RequestMapping("/api/v1/accounts/{accountId}/transactions")
@RequiredArgsConstructor
public class AccountTransactionController {

    private final LedgerQueryService queryService;
    private final AccountOwnershipGuard ownershipGuard;

    /**
     * 계좌의 거래 내역을 최신순으로 조회합니다.
     *
     * @param from 이 시각 이상 (ISO-8601, 선택)
     * @param to   이 시각 미만 (ISO-8601, 선택)
     */
    @GetMapping
    public ResponseEntity<TransactionPage> listTransactions(
            @PathVariable UUID accountId,
            @RequestParam(name = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(name = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {
        ownershipGuard.requireOwnership(accountId);
        return ResponseEntity.ok(queryService.findTransactions(
                accountId, from, to, Math.max(page, 0), Math.clamp(size, 1, 100)));
    }

    /**
     * 거래 하나에서 이 계좌의 분개만 조회합니다.
     */
    @GetMapping("/{transactionId}")
    public ResponseEntity<TransactionDetail> getTransaction(
            @PathVariable UUID accountId,
            @PathVariable UUID transactionId) {
        ownershipGuard.requireOwnership(accountId);
        return ResponseEntity.ok(queryService.findTransaction(accountId, transactionId));
    }
}
