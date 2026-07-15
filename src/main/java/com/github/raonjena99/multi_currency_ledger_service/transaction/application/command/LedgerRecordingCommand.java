package com.github.raonjena99.multi_currency_ledger_service.transaction.application.command;

import java.math.BigDecimal;
import java.util.UUID;

import com.github.raonjena99.multi_currency_ledger_service.common.domain.Money;

/**
 * 원장 기록을 위한 LedgerRecordingCommand(명령) 레코드입니다.
 */
public record LedgerRecordingCommand(
    UUID referenceTradeId,
    UUID accountId,
    String assetCode,
    String fiatCode,
    String tradeType,
    Money quantity,
    Money unitPrice,
    BigDecimal exchangeRate,
    Money averageCost,
    boolean isStaleRate
) {}

