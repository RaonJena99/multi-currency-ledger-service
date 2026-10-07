package com.github.raonjena99.multi_currency_ledger_service.account.domain.event;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

import com.github.raonjena99.multi_currency_ledger_service.common.model.AssetType;
import com.github.raonjena99.multi_currency_ledger_service.common.model.TradeType;

/**
 * TradeExecutedEvent 레코드.
 * 주문/계좌 컨텍스트에서 거래(매수/매도/입금/출금)가 성공적으로 완료되어 잔고에 반영되었음을 알리는 도메인 이벤트입니다.
 *
 * <p>입출금도 이 이벤트로 발행합니다. 잔고가 바뀌고 복식부기 분개가 필요하다는 점이 매수·매도와 같아서,
 * 아웃박스 적재와 포트폴리오 캐시 갱신 경로를 그대로 탈 수 있습니다. 입출금은 자산 코드와 결제 통화가
 * 모두 입출금 통화이고, 단가와 환율은 1 입니다.
 *
 * @param tradeId 거래 고유 ID
 * @param accountId 거래가 발생한 Account(계좌) ID
 * @param assetCode 거래 대상 자산 코드
 * @param assetType 거래 대상 자산 유형
 * @param fiatCode 기준 법정 화폐 코드
 * @param tradeType 거래 유형 (매수/매도/입금/출금)
 * @param baseCurrency 계좌의 기준 통화 코드
 * @param quantity 거래 수량
 * @param unitPrice 거래 단가
 * @param exchangeRate 대상 자산 → 결제 통화 환율
 * @param fiatToBaseRate 결제 통화 → 기준 통화 환율. 거래 시점에 <b>실제로 적용된</b> 값이며,
 *                       원장 기록 단계에서 환율을 다시 조회하지 않고 이 값을 그대로 사용해야
 *                       잔고와 분개가 같은 환율로 기록됩니다.
 * @param averageCost 계좌에서 <b>나가는 쪽</b> 원장의 차감 직전 평균 단가(기준 통화). 매도는 자산,
 *                    매수는 결제 통화, 출금은 출금 통화의 평균 단가이며 원장이 이 값으로 실현 손익을 계산합니다.
 *                    입금처럼 나가는 쪽이 없으면 0 입니다.
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