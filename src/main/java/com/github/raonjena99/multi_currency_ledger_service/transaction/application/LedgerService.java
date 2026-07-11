package com.github.raonjena99.multi_currency_ledger_service.transaction.application;

import java.math.BigDecimal;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.github.raonjena99.multi_currency_ledger_service.common.domain.Money;
import com.github.raonjena99.multi_currency_ledger_service.common.model.AssetType;
import com.github.raonjena99.multi_currency_ledger_service.transaction.application.command.LedgerRecordingCommand;
import com.github.raonjena99.multi_currency_ledger_service.transaction.domain.Transaction;
import com.github.raonjena99.multi_currency_ledger_service.transaction.infrastructure.TransactionRepository;

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
        
        // 매수(BUY) 거래인 경우: 자산을 매수하고 법정화폐를 매도(지불)합니다.
        if ("BUY".equals(cmd.tradeType())) {
            Money requiredFiatAmount = cmd.unitPrice().multiply(cmd.quantity().getAmount());
            
            // 차변(Debit): 매수한 자산 증가 기록
            transaction.addBuyEntry(cmd.accountId(), cmd.assetCode(), cmd.quantity(), cmd.unitPrice(), cmd.exchangeRate(), cmd.fiatCode());
            // 대변(Credit): 지불한 법정화폐 감소 기록
            transaction.addSellEntry(cmd.accountId(), cmd.fiatCode(), requiredFiatAmount, 
                                    Money.of("1", AssetType.FIAT, cmd.fiatCode()), BigDecimal.ONE, Money.of("1", AssetType.FIAT, cmd.fiatCode()), cmd.fiatCode());
        } else if ("SELL".equals(cmd.tradeType())) {
            // 매도(SELL) 거래인 경우: 법정화폐를 매수(수취)하고 자산을 매도합니다.
            Money earnedFiatAmount = cmd.unitPrice().multiply(cmd.quantity().getAmount());
            
            // 차변(Debit): 수취한 법정화폐 증가 기록
            transaction.addBuyEntry(cmd.accountId(), cmd.fiatCode(), earnedFiatAmount, 
                                    Money.of("1", AssetType.FIAT, cmd.fiatCode()), BigDecimal.ONE, cmd.fiatCode());
            // 대변(Credit): 매도한 자산 감소 기록
            transaction.addSellEntry(cmd.accountId(), cmd.assetCode(), cmd.quantity(), 
                                    cmd.unitPrice(), cmd.exchangeRate(), cmd.averageCost(), cmd.fiatCode());
        }

        transactionRepository.save(transaction);
        log.info("Ledger successfully recorded for TradeID: {}", cmd.referenceTradeId());
    }
}