package com.github.raonjena99.multi_currency_ledger_service.account.application;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.github.raonjena99.multi_currency_ledger_service.account.AccountApi;
import com.github.raonjena99.multi_currency_ledger_service.account.domain.Account;
import com.github.raonjena99.multi_currency_ledger_service.account.domain.MonthlyAccountLedger;
import com.github.raonjena99.multi_currency_ledger_service.account.domain.event.BalanceAdjustedEvent;
import com.github.raonjena99.multi_currency_ledger_service.account.infrastructure.AccountRepository;
import com.github.raonjena99.multi_currency_ledger_service.account.infrastructure.MonthlyAccountLedgerRepository;
import com.github.raonjena99.multi_currency_ledger_service.common.domain.Money;
import com.github.raonjena99.multi_currency_ledger_service.common.exception.AccountNotFoundException;
import com.github.raonjena99.multi_currency_ledger_service.common.model.AssetType;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 이 클래스는 계좌의 기본 통화 정보를 조회하는 기능을 담당합니다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AccountApiImpl implements AccountApi {

    private final AccountRepository accountRepository;

    private final MonthlyAccountLedgerRepository monthlyAccountLedgerRepository;

    private final LedgerPeriodResolver ledgerPeriodResolver;

    private final MonthlyLedgerResolver monthlyLedgerResolver;

    private final ApplicationEventPublisher eventPublisher;

    @Override
    public String getBaseCurrency(UUID accountId) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new AccountNotFoundException(accountId));
        return account.getBaseCurrency();
    }

    @Override
    public List<AccountBalanceDto> getBalances(UUID accountId) {
        return monthlyAccountLedgerRepository.findLatestBalancesByAccountId(accountId).stream()
                .map(ledger -> new AccountBalanceDto(
                        ledger.getAssetCode(),
                        ledger.getBalance().getAmount(),
                        ledger.getAverageUnitPrice(),
                        ledger.getBaseCurrency()
                )).toList();
    }

    /**
     * {@inheritDoc}
     *
     * <p>호출자의 트랜잭션에 참여합니다. 대사 수수료 보정 경로에서는 원장 분개 기록과 같은
     * 트랜잭션 안에서 호출되므로, 분개만 남고 잔고가 반영되지 않는(또는 그 반대) 부분 실패가
     * 구조적으로 불가능합니다. 중복 반영 방지도 분개 쪽 거래 ID 멱등성 검사에 함께 묶입니다.
     */
    @Override
    @Transactional
    public void applyFiatBalanceAdjustment(UUID accountId, Money adjustment, OffsetDateTime transactedAt) {
        if (adjustment.isZero()) {
            return;
        }
        if (adjustment.getAssetType() != AssetType.FIAT) {
            throw new IllegalArgumentException(
                    "Balance adjustment must be a FIAT amount, but was " + adjustment.getAssetType());
        }

        String ledgerMonth = ledgerPeriodResolver.resolveLedgerMonth(accountId, transactedAt);
        MonthlyAccountLedger fiatLedger = monthlyLedgerResolver.resolveOrInitializeLedger(
                accountId, adjustment.getCurrencyCode(), AssetType.FIAT, ledgerMonth);

        // 보정은 매수/매도가 아니라 회계 정정이므로 전용 메서드를 쓴다. 잔고 부족으로 분개까지
        // 막지 않고(음수 잔고 = 고객 채권), 이동 평균 단가도 왜곡하지 않는다.
        fiatLedger.applyAdjustment(adjustment);

        monthlyAccountLedgerRepository.save(fiatLedger);
        eventPublisher.publishEvent(new BalanceAdjustedEvent(accountId, adjustment, transactedAt));

        log.info("Fiat balance adjusted. accountId={}, amount={} {}, ledgerMonth={}",
                accountId, adjustment.getAmount().toPlainString(), adjustment.getCurrencyCode(), ledgerMonth);
    }
}
