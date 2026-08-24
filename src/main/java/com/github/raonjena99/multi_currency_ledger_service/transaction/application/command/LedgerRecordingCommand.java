package com.github.raonjena99.multi_currency_ledger_service.transaction.application.command;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.github.raonjena99.multi_currency_ledger_service.common.domain.Money;
import com.github.raonjena99.multi_currency_ledger_service.common.model.AssetType;

/**
 * 원장 기록을 위한 LedgerRecordingCommand(명령) 레코드입니다.
 *
 * @param fiatToBaseRate 결제 통화 → 기준 통화 환율. 거래 시점에 <b>실제로 적용된</b> 값입니다.
 *                       원장 기록 단계에서 환율을 재조회하면 잔고와 분개가 서로 다른 환율로
 *                       기록되므로 반드시 이 값을 사용해야 합니다.
 * @param transactedAt   거래가 발생한 시각. 원장의 transacted_at 은 소비 시각이 아니라 이 값을
 *                       따라야 월차 원장 귀속월과 어긋나지 않습니다.
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
    BigDecimal unitPrice,
    BigDecimal exchangeRate,
    BigDecimal fiatToBaseRate,
    BigDecimal averageCost,
    boolean isStaleRate,
    OffsetDateTime transactedAt
) {}
