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

@Slf4j
@Service
@RequiredArgsConstructor
public class LedgerService {

    private final TransactionRepository transactionRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordDoubleEntry(LedgerRecordingCommand cmd) {
        if (transactionRepository.existsById(cmd.referenceTradeId())) {
            log.warn("Ledger already recorded for TradeID: {}. Ignoring duplicate request.", cmd.referenceTradeId());
            return;
        }

        Transaction transaction = new Transaction(
            cmd.referenceTradeId(), 
            cmd.tradeType(), 
            "Auto-recorded via ACL. Ref TradeID: " + cmd.referenceTradeId()
        );
        
        if ("BUY".equals(cmd.tradeType())) {
            Money requiredFiatAmount = cmd.unitPrice().multiply(cmd.quantity().getAmount());
            
            transaction.addBuyEntry(cmd.accountId(), cmd.assetCode(), cmd.quantity(), cmd.unitPrice(), cmd.exchangeRate());
            transaction.addSellEntry(cmd.accountId(), cmd.fiatCode(), requiredFiatAmount, 
                                    Money.of("1", AssetType.FIAT), BigDecimal.ONE, Money.of("1", AssetType.FIAT));
        } else if ("SELL".equals(cmd.tradeType())) {
            Money earnedFiatAmount = cmd.unitPrice().multiply(cmd.quantity().getAmount());
            
            transaction.addBuyEntry(cmd.accountId(), cmd.fiatCode(), earnedFiatAmount, 
                                    Money.of("1", AssetType.FIAT), BigDecimal.ONE);
            transaction.addSellEntry(cmd.accountId(), cmd.assetCode(), cmd.quantity(), 
                                    cmd.unitPrice(), cmd.exchangeRate(), cmd.averageCost());
        }

        transactionRepository.save(transaction);
        log.info("Ledger successfully recorded for TradeID: {}", cmd.referenceTradeId());
    }
}