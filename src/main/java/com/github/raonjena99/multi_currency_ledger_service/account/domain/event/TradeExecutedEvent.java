package com.github.raonjena99.multi_currency_ledger_service.account.domain.event;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

import com.github.raonjena99.multi_currency_ledger_service.common.model.AssetType;
import com.github.raonjena99.multi_currency_ledger_service.common.model.TradeType;

/**
 * 주문/계좌 컨텍스트에서 거래(매수/매도)가 성공적으로 완료(잔고 반영)되었음을 알리는 도메인 이벤트
 */
public record TradeExecutedEvent(
    UUID tradeId,            
    UUID accountId,          
    String assetCode,        
    AssetType assetType,     
    String fiatCode,         
    TradeType tradeType,     
    BigDecimal quantity,     
    BigDecimal unitPrice,    
    BigDecimal exchangeRate, 
    BigDecimal averageCost,  
    boolean isStaleRate,
    OffsetDateTime occurredAt 
) {
    public TradeExecutedEvent {
        Objects.requireNonNull(tradeId, "Trade ID cannot be null");
        Objects.requireNonNull(accountId, "Account ID cannot be null");
        Objects.requireNonNull(assetCode, "Asset code cannot be null");
        Objects.requireNonNull(assetType, "Asset type cannot be null");
        Objects.requireNonNull(fiatCode, "Fiat code cannot be null");
        Objects.requireNonNull(tradeType, "Trade type cannot be null");
        Objects.requireNonNull(quantity, "Quantity cannot be null");
        Objects.requireNonNull(unitPrice, "Unit price cannot be null");
        
        if (occurredAt == null) {
            occurredAt = OffsetDateTime.now();
        }
    }
}