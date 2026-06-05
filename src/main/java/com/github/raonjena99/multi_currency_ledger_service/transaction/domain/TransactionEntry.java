package com.github.raonjena99.multi_currency_ledger_service.transaction.domain;

import java.math.BigDecimal;
import java.util.UUID;

import com.github.raonjena99.multi_currency_ledger_service.common.model.AssetType;
import com.github.raonjena99.multi_currency_ledger_service.common.model.EntryType;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "transaction_entries")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TransactionEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "transaction_id", nullable = false)
    private Transaction transaction;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Enumerated(EnumType.STRING)
    @Column(name = "entry_type", nullable = false, length = 10)
    private EntryType entryType;

    @Column(name = "asset_code", nullable = false, length = 20)
    private String assetCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "asset_type", nullable = false, length = 20)
    private AssetType assetType;

    @Column(nullable = false, precision = 36, scale = 18)
    private BigDecimal quantity;

    @Column(name = "unit_price", nullable = false, precision = 36, scale = 18)
    private BigDecimal unitPrice;

    @Column(name = "realized_pnl", precision = 36, scale = 18)
    private BigDecimal realizedPnl;

    @Column(name = "exchange_rate", precision = 19, scale = 6)
    private BigDecimal exchangeRate;

    @Column(nullable = false, precision = 36, scale = 18)
    private BigDecimal amount;

    // 패키지 프라이빗: 외부에서 직접 생성하지 못하고 Transaction 을 통해서만 생성되도록 제어
    private TransactionEntry(Transaction transaction, UUID accountId, EntryType entryType, String assetCode, AssetType assetType, 
                    BigDecimal quantity, BigDecimal unitPrice, BigDecimal exchangeRate) {
        this.transaction = transaction;
        this.accountId = accountId;
        this.entryType = entryType;
        this.assetCode = assetCode;
        this.assetType = assetType;

        // 정밀도 조정
        this.quantity = assetType.normalize(quantity);

        // 기준 통화(FIAT) 기준으로 처리
        this.unitPrice = AssetType.FIAT.normalize(unitPrice);
        this.exchangeRate = exchangeRate != null ? exchangeRate : BigDecimal.ONE;
        
        // 기준 통화 환산액 계산
        BigDecimal calculatedAmount = this.quantity.multiply(this.unitPrice).multiply(this.exchangeRate);
        this.amount = AssetType.FIAT.normalize(calculatedAmount);
    }

    // 매수 분개장 생성
    public static TransactionEntry createBuyEntry(
            Transaction transaction, UUID accountId, String assetCode, AssetType assetType, 
            BigDecimal buyQuantity, BigDecimal buyPrice, BigDecimal exchangeRate) {
        
        // 차변(DEBIT)에 기록
        TransactionEntry entry = new TransactionEntry(
                transaction, accountId, EntryType.DEBIT, assetCode, assetType, 
                buyQuantity, buyPrice, exchangeRate
        );
        
        // 매수 시점에는 실현 손익이 발생하지 않음
        entry.realizedPnl = BigDecimal.ZERO;
        
        return entry;
    }

    // 매도 분개장 생성
    public static TransactionEntry createSellEntry(
            Transaction transaction, UUID accountId, String assetCode, AssetType assetType, 
            BigDecimal sellQuantity, BigDecimal sellPrice, BigDecimal exchangeRate, BigDecimal averageCost) {
        
        // 대변(CREDIT)에 기록
        TransactionEntry entry = new TransactionEntry(
                transaction, accountId, EntryType.CREDIT, assetCode, assetType, 
                sellQuantity, sellPrice, exchangeRate
        );
        
        BigDecimal pnl = sellPrice.subtract(averageCost).multiply(sellQuantity);
        
        // 실현 손익 정밀도 조정
        entry.realizedPnl = AssetType.FIAT.normalize(pnl);
        
        return entry;
    }
}