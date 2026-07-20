package com.github.raonjena99.multi_currency_ledger_service.transaction.application.command;

import java.math.BigDecimal;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.github.raonjena99.multi_currency_ledger_service.common.domain.Money;
import com.github.raonjena99.multi_currency_ledger_service.common.model.AssetType;

/**
 * 원장 기록을 위한 LedgerRecordingCommand(명령) 레코드입니다.
 */
public record LedgerRecordingCommand(
    @JsonAlias({"tradeId", "settlementId"})
    UUID referenceTradeId,
    UUID accountId,
    @JsonAlias("targetAssetCode")
    String assetCode,
    @JsonAlias("targetAssetType")
    AssetType assetType,
    @JsonAlias("paymentCurrency")
    String fiatCode,
    String baseCurrency,
    String tradeType,
    Money quantity,
    Money unitPrice,
    BigDecimal exchangeRate,
    Money averageCost,
    boolean isStaleRate
) {}

