package com.github.raonjena99.multi_currency_ledger_service.transaction.application;

import java.math.BigDecimal;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.github.raonjena99.multi_currency_ledger_service.common.domain.Money;
import com.github.raonjena99.multi_currency_ledger_service.common.model.AssetType;
import com.github.raonjena99.multi_currency_ledger_service.common.model.EntryType;
import com.github.raonjena99.multi_currency_ledger_service.transaction.application.command.LedgerRecordingCommand;
import com.github.raonjena99.multi_currency_ledger_service.transaction.domain.Transaction;
import com.github.raonjena99.multi_currency_ledger_service.transaction.infrastructure.TransactionRepository;
import com.github.raonjena99.multi_currency_ledger_service.common.port.ExchangeRateProvider;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 복식 부기 원장 기록 로직을 담당하는 LedgerService(원장 서비스) 클래스입니다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LedgerService {

    private final TransactionRepository transactionRepository;
    private final ExchangeRateProvider exchangeRateProvider;

    /**
     * 복식 부기 원장 기록을 수행합니다.
     * @param cmd 원장 기록을 위한 LedgerRecordingCommand(명령) 객체
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordDoubleEntry(LedgerRecordingCommand cmd) {
        // 거래 ID로 중복 기록 여부를 확인하여 멱등성(Idempotency)을 보장합니다.
        if (transactionRepository.existsById(cmd.referenceTradeId())) {
            log.warn("Ledger already recorded for TradeID: {}. Ignoring duplicate request.", cmd.referenceTradeId());
            return;
        }

        String description = "Auto-recorded via ACL. Ref TradeID: " + cmd.referenceTradeId();
        if (cmd.isStaleRate()) {
            description += " [APPLIED_FALLBACK_RATE=TRUE]";
            log.warn("Fallback 환율이 적용된 거래가 원장에 기록됩니다. 향후 정산 대사(Reconciliation) 시 오차 허용 룰 엔진의 타겟이 됩니다. TradeID: {}", cmd.referenceTradeId());
        }

        Transaction transaction = Transaction.record(
            cmd.referenceTradeId(), 
            cmd.tradeType(), 
            description 
        );
        
        // [수정1: 환율 적용] 외화 결제 통화(Fiat)와 시스템 원장 기준 통화(Base Currency) 간의 정확한 환율 계산
        BigDecimal fiatToBaseRate = BigDecimal.ONE;
        String baseCurrency = cmd.baseCurrency() != null ? cmd.baseCurrency() : cmd.fiatCode();
        
        if (!cmd.fiatCode().equals(baseCurrency)) {
            var rateInfo = exchangeRateProvider.getExchangeRate(cmd.fiatCode(), baseCurrency);
            fiatToBaseRate = rateInfo.rate();
        }

        // 매수(BUY) 거래인 경우: 자산을 매수하고 법정화폐를 매도(지불)합니다.
        if ("BUY".equals(cmd.tradeType())) {
            Money requiredFiatAmount = cmd.unitPrice().multiply(cmd.quantity().getAmount());
            
            // 차변(Debit): 매수한 자산 증가 기록 (fiatToBaseRate 적용)
            transaction.addBuyEntry(cmd.accountId(), cmd.assetCode(), cmd.quantity(), cmd.unitPrice(), fiatToBaseRate, baseCurrency);
            // 대변(Credit): 지불한 법정화폐 감소 기록 (환율 미적용 버그 해결)
            transaction.addSellEntry(cmd.accountId(), cmd.fiatCode(), requiredFiatAmount, 
                                    Money.of("1", AssetType.FIAT, cmd.fiatCode()), fiatToBaseRate, Money.of("1", AssetType.FIAT, cmd.fiatCode()), baseCurrency);
        } else if ("SELL".equals(cmd.tradeType())) {
            // 매도(SELL) 거래인 경우: 법정화폐를 매수(수취)하고 자산을 매도합니다.
            Money earnedFiatAmount = cmd.unitPrice().multiply(cmd.quantity().getAmount());
            
            // 차변(Debit): 수취한 법정화폐 증가 기록
            transaction.addBuyEntry(cmd.accountId(), cmd.fiatCode(), earnedFiatAmount, 
                                    Money.of("1", AssetType.FIAT, cmd.fiatCode()), fiatToBaseRate, baseCurrency);
            // 대변(Credit): 매도한 자산 감소 기록
            transaction.addSellEntry(cmd.accountId(), cmd.assetCode(), cmd.quantity(), 
                                    cmd.unitPrice(), fiatToBaseRate, cmd.averageCost(), baseCurrency);
        } else if ("FEE_DEDUCTION".equals(cmd.tradeType())) {
            // 수수료 차감 로직
            UUID systemFeeAccountId = UUID.fromString("00000000-0000-0000-0000-000000000001");
            
            // [수정2: 수수료 하드코딩 제거] 코인 수수료 차감 시에도 대응 가능하도록 수정
            transaction.addSellEntry(cmd.accountId(), cmd.assetCode(), cmd.quantity(), 
                                    cmd.unitPrice(), fiatToBaseRate, 
                                    cmd.averageCost() != null ? cmd.averageCost() : cmd.unitPrice(), baseCurrency);
            
            transaction.addBuyEntry(systemFeeAccountId, cmd.assetCode(), cmd.quantity(), 
                                    cmd.unitPrice(), fiatToBaseRate, baseCurrency);
        } else if ("FEE_ADJUSTMENT".equals(cmd.tradeType())) {
            
            // 정산 수수료 오차 반영
            if (cmd.quantity().getAmount().compareTo(BigDecimal.ZERO) > 0) {
                // 수수료 수익 (초과 수취): 차변(법정화폐 입금), 대변(수수료 수익)
                transaction.addBuyEntry(cmd.accountId(), cmd.fiatCode(), cmd.quantity(), 
                                        Money.of("1", AssetType.FIAT, cmd.fiatCode()), fiatToBaseRate, baseCurrency);
                                        
                transaction.addSellEntry(cmd.accountId(), "FEE_GAIN", cmd.quantity(), 
                                        Money.of("1", AssetType.FIAT, cmd.fiatCode()), fiatToBaseRate, 
                                        Money.of("1", AssetType.FIAT, cmd.fiatCode()), baseCurrency);
            } else if (cmd.quantity().getAmount().compareTo(BigDecimal.ZERO) < 0) {
                // 수수료 손실 (초과 지불): 차변(수수료 손실), 대변(법정화폐 출금)
                Money lossAmount = Money.of(cmd.quantity().getAmount().abs().toPlainString(), AssetType.FIAT, cmd.fiatCode());

                transaction.addBuyEntry(cmd.accountId(), "FEE_LOSS", lossAmount, 
                                        Money.of("1", AssetType.FIAT, cmd.fiatCode()), fiatToBaseRate, baseCurrency);

                transaction.addSellEntry(cmd.accountId(), cmd.fiatCode(), lossAmount, 
                                        Money.of("1", AssetType.FIAT, cmd.fiatCode()), fiatToBaseRate, 
                                        Money.of("1", AssetType.FIAT, cmd.fiatCode()), baseCurrency);
            }
        }

        // [수정1: 환율 적용] 차액 집계 시 기준 통화(Base Currency)로 집계
        BigDecimal totalDebit = transaction.getEntries().stream()
            .filter(e -> e.getEntryType() == EntryType.DEBIT && e.getAmount().getCurrencyCode().equals(baseCurrency))
            .map(e -> e.getAmount().getAmount())
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalCredit = transaction.getEntries().stream()
            .filter(e -> e.getEntryType() == EntryType.CREDIT && e.getAmount().getCurrencyCode().equals(baseCurrency))
            .map(e -> e.getAmount().getAmount())
            .reduce(BigDecimal.ZERO, BigDecimal::add);
            
        // (매도 시) 대변에 함께 기록된 실현 손익(Realized PnL)도 대변 합계에 합산
        BigDecimal totalRealizedPnl = transaction.getEntries().stream()
            .filter(e -> e.getRealizedPnl() != null && !e.getRealizedPnl().isZero())
            .map(e -> e.getRealizedPnl().getAmount())
            .reduce(BigDecimal.ZERO, BigDecimal::add);
            
        totalCredit = totalCredit.add(totalRealizedPnl);
        // 차액 = 차변 - 대변
        BigDecimal difference = totalDebit.subtract(totalCredit);
        // 시스템 고유 계좌 ID
        UUID systemAccountId = new UUID(0, 0); 

        // 3. 차액이 발생한 경우 시스템 계정으로 Plug-in
        if (difference.compareTo(BigDecimal.ZERO) > 0) {
            // 차변 > 대변: 시스템 환차익 발생 -> 대변(Sell) 엔트리로 SYSTEM_FX_GAIN 추가
            Money differenceMoney = Money.of(difference.toPlainString(), AssetType.FIAT, baseCurrency);
            transaction.addSellEntry(systemAccountId, "SYSTEM_FX_GAIN", differenceMoney,
                                    Money.of("1", AssetType.FIAT, baseCurrency), BigDecimal.ONE, 
                                    Money.of("1", AssetType.FIAT, baseCurrency), baseCurrency);
                                    
        } else if (difference.compareTo(BigDecimal.ZERO) < 0) {
            // 차변 < 대변: 시스템 환차손 발생 -> 차변(Buy) 엔트리로 SYSTEM_FX_LOSS 추가
            Money differenceMoney = Money.of(difference.abs().toPlainString(), AssetType.FIAT, baseCurrency);
            transaction.addBuyEntry(systemAccountId, "SYSTEM_FX_LOSS", differenceMoney,
                                    Money.of("1", AssetType.FIAT, baseCurrency), BigDecimal.ONE, baseCurrency);
        }

        // [수정3: 레이스 컨디션 방지] Check-Then-Act 문제 해결을 위해 Unique Constraint를 활용한 DB 락 멱등성 보장
        try {
            transactionRepository.saveAndFlush(transaction);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            log.warn("Ledger already recorded for TradeID: {} (Caught by DB Constraint). Ignoring duplicate request.", cmd.referenceTradeId());
            return;
        }
        log.info("Ledger successfully recorded for TradeID: {}", cmd.referenceTradeId());
    }
}