package com.github.raonjena99.multi_currency_ledger_service.account.domain.event;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

import com.github.raonjena99.multi_currency_ledger_service.common.model.AssetType;
import com.github.raonjena99.multi_currency_ledger_service.common.model.TradeType;

/**
 * TradeExecutedEvent 레코드.
 * 주문/계좌 컨텍스트에서 거래(매수/매도)가 성공적으로 완료되어 잔고에 반영되었음을 알리는 도메인 이벤트입니다.
 *
 * @param tradeId 거래 고유 ID
 * @param accountId 거래가 발생한 Account(계좌) ID
 * @param assetCode 거래 대상 자산 코드
 * @param assetType 거래 대상 자산 유형
 * @param fiatCode 기준 법정 화폐 코드
 * @param tradeType 거래 유형 (매수/매도)
 * @param baseCurrency 계좌의 기준 통화 코드
 * @param quantity 거래 수량
 * @param unitPrice 거래 단가
 * @param exchangeRate 대상 자산 → 결제 통화 환율
 * @param fiatToBaseRate 결제 통화 → 기준 통화 환율. 거래 시점에 <b>실제로 적용된</b> 값이며,
 *                       원장 기록 단계에서 환율을 다시 조회하지 않고 이 값을 그대로 사용해야
 *                       잔고와 분개가 같은 환율로 기록됩니다.
 * @param averageCost 평균 매입 단가
 * @param isStaleRate 적용된 환율의 지연(stale) 여부
 * @param occurredAt 이벤트 발생 시간
 */
public record TradeExecutedEvent(
    UUID tradeId,
    UUID accountId,
    String assetCode,
    AssetType assetType,
    String fiatCode,
    String baseCurrency,
    TradeType tradeType,
    BigDecimal quantity,
    BigDecimal unitPrice,
    BigDecimal exchangeRate,
    BigDecimal fiatToBaseRate,
    BigDecimal averageCost,
    boolean isStaleRate,
    OffsetDateTime occurredAt
) {
    /**
     * TradeExecutedEvent 생성자.
     * 필수 필드에 대한 null 검증을 수행하고, 발생 시간(occurredAt)이 없을 경우 현재 시간으로 설정합니다.
     */
    public TradeExecutedEvent {
        Objects.requireNonNull(tradeId, "Trade ID cannot be null");
        Objects.requireNonNull(accountId, "Account ID cannot be null");
        Objects.requireNonNull(assetCode, "Asset code cannot be null");
        Objects.requireNonNull(assetType, "Asset type cannot be null");
        Objects.requireNonNull(fiatCode, "Fiat code cannot be null");
        Objects.requireNonNull(baseCurrency, "Base currency cannot be null");
        Objects.requireNonNull(tradeType, "Trade type cannot be null");
        Objects.requireNonNull(quantity, "Quantity cannot be null");
        Objects.requireNonNull(unitPrice, "Unit price cannot be null");

        if (fiatToBaseRate == null) {
            fiatToBaseRate = BigDecimal.ONE;
        }
        if (occurredAt == null) {
            occurredAt = OffsetDateTime.now();
        }
    }
}