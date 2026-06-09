package com.github.raonjena99.multi_currency_ledger_service.account.domain.event;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * 주문/계좌 컨텍스트에서 거래(매수/매도)가 성공적으로 완료(잔고 반영)되었음을 알리는 도메인 이벤트
 */
public record TradeExecutedEvent(
    UUID tradeId,            // 주문/거래의 고유 ID
    UUID accountId,          // 계좌 ID
    String assetCode,        // 매매 대상 자산 (예: TSLA, BTC)
    String assetType,        // 매매 대상 자산의 유형 (예: CRYPTO, STOCK)
    String fiatCode,         // 기축 통화 (예: KRW)
    String tradeType,        // "BUY" or "SELL"
    BigDecimal quantity,     // 거래 수량 (Money의 amount)
    BigDecimal unitPrice,    // 거래 단가 (Money의 amount)
    BigDecimal exchangeRate, // 적용 환율
    BigDecimal averageCost   // 매도시 계산된 평균 단가
) {}
