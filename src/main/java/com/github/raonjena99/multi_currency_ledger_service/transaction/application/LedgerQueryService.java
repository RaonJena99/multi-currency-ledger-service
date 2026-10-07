package com.github.raonjena99.multi_currency_ledger_service.transaction.application;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.github.raonjena99.multi_currency_ledger_service.common.exception.TransactionNotFoundException;
import com.github.raonjena99.multi_currency_ledger_service.transaction.infrastructure.query.LedgerQueryDao;
import com.github.raonjena99.multi_currency_ledger_service.transaction.infrastructure.query.LedgerQueryDao.CurrencyTotalsView;
import com.github.raonjena99.multi_currency_ledger_service.transaction.infrastructure.query.LedgerQueryDao.EntryView;
import com.github.raonjena99.multi_currency_ledger_service.transaction.infrastructure.query.LedgerQueryDao.TransactionSummaryView;

import lombok.RequiredArgsConstructor;

/**
 * 거래 내역, 거래별 분개, 시산표를 조회합니다.
 *
 * <p>분개는 Kafka 를 거쳐 비동기로 기록되므로, 거래 직후에는 아직 조회되지 않을 수 있습니다.
 */
@Service
@RequiredArgsConstructor
public class LedgerQueryService {

    private final LedgerQueryDao queryDao;

    public record TransactionPage(long totalCount, int page, int size, List<TransactionSummaryView> transactions) {}

    public record TransactionDetail(UUID transactionId, String transactionType, OffsetDateTime transactedAt,
                                    List<EntryView> entries) {}

    public record CurrencyTrialBalance(String currency, BigDecimal debitTotal, BigDecimal creditTotal,
                                       BigDecimal realizedPnlTotal, boolean balanced) {}

    public record TrialBalance(String month, boolean balanced, List<CurrencyTrialBalance> currencies) {}

    /**
     * 계좌의 거래 내역을 최신순으로 조회합니다.
     *
     * @param from 이 시각 이상 (null 이면 제한 없음)
     * @param to   이 시각 미만 (null 이면 제한 없음)
     */
    @Transactional(readOnly = true)
    public TransactionPage findTransactions(UUID accountId, OffsetDateTime from, OffsetDateTime to,
                                            int page, int size) {
        if (from != null && to != null && !from.isBefore(to)) {
            throw new IllegalArgumentException("조회 시작 시각은 끝 시각보다 앞서야 합니다.");
        }
        long total = queryDao.countAccountTransactions(accountId, from, to);
        List<TransactionSummaryView> rows = queryDao.findAccountTransactions(accountId, from, to, page * size, size);
        return new TransactionPage(total, page, size, rows);
    }

    /**
     * 거래 하나에서 그 계좌의 분개만 조회합니다.
     *
     * <p>시스템 계정(반올림 잔차, 수수료, 입출금 청산) 분개는 고객에게 보여주지 않습니다.
     * 그 계좌의 분개가 없는 거래는 다른 계좌의 거래이거나 아직 기록되지 않은 거래이므로 404 로 응답합니다.
     * 거래 ID 만 알면 남의 거래를 볼 수 있어서는 안 됩니다.
     *
     * @throws TransactionNotFoundException 그 계좌의 분개가 없는 경우
     */
    @Transactional(readOnly = true)
    public TransactionDetail findTransaction(UUID accountId, UUID transactionId) {
        TransactionSummaryView summary = queryDao.findAccountTransaction(accountId, transactionId)
                .orElseThrow(() -> new TransactionNotFoundException(transactionId));
        return new TransactionDetail(summary.transactionId(), summary.transactionType(), summary.transactedAt(),
                queryDao.findEntries(transactionId, accountId));
    }

    /**
     * 한 달 동안의 분개를 기준 통화별로 집계해 차대가 맞는지 보여줍니다.
     *
     * <p>실현 손익은 고객 계정의 별도 분개라 합계에 이미 들어 있으므로 차대는 {@code 차변 = 대변} 입니다.
     * {@code realizedPnlTotal} 은 그 손익 분개의 순합(이익 − 손실)으로 대차 판정에는 쓰지 않습니다.
     * 월은 {@code transacted_at} 을 UTC 로 바꿔 정합니다. 월차 원장 귀속월과 같은 기준입니다.
     */
    @Transactional(readOnly = true)
    public TrialBalance trialBalance(YearMonth month) {
        OffsetDateTime start = month.atDay(1).atStartOfDay().atOffset(ZoneOffset.UTC);
        OffsetDateTime end = month.plusMonths(1).atDay(1).atStartOfDay().atOffset(ZoneOffset.UTC);

        List<CurrencyTrialBalance> currencies = queryDao.sumByCurrency(start, end).stream()
                .map(LedgerQueryService::toTrialBalance)
                .toList();
        boolean allBalanced = currencies.stream().allMatch(CurrencyTrialBalance::balanced);
        return new TrialBalance(month.toString(), allBalanced, currencies);
    }

    private static CurrencyTrialBalance toTrialBalance(CurrencyTotalsView totals) {
        boolean balanced = totals.debitTotal().compareTo(totals.creditTotal()) == 0;
        return new CurrencyTrialBalance(totals.currency(), totals.debitTotal(), totals.creditTotal(),
                totals.realizedPnlTotal(), balanced);
    }
}
