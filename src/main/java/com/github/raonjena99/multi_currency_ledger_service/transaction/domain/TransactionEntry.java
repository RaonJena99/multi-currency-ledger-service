package com.github.raonjena99.multi_currency_ledger_service.transaction.domain;

import java.math.BigDecimal;
import java.util.UUID;

import com.github.raonjena99.multi_currency_ledger_service.common.domain.Money;
import com.github.raonjena99.multi_currency_ledger_service.common.model.AssetType;
import com.github.raonjena99.multi_currency_ledger_service.common.model.EntryType;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Index;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "transaction_entries", indexes = {
    @Index(name = "idx_transaction_id", columnList = "transaction_id"),
    @Index(name = "idx_account_id", columnList = "account_id")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TransactionEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "tx_entry_seq")
    @SequenceGenerator(name = "tx_entry_seq", sequenceName = "transaction_entry_seq", allocationSize = 50)
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

    @Embedded
    @AttributeOverrides({
        @AttributeOverride(name = "amount", column = @Column(name = "quantity", nullable = false, precision = 36, scale = 18)),
        @AttributeOverride(name = "assetType", column = @Column(name = "quantity_asset_type", nullable = false, length = 20)),
        @AttributeOverride(name = "currencyCode", column = @Column(name = "quantity_currency", nullable = false, length = 10))
    })
    private Money quantity;

    @Embedded
    @AttributeOverrides({
        @AttributeOverride(name = "amount", column = @Column(name = "unit_price", nullable = false, precision = 36, scale = 18)),
        @AttributeOverride(name = "assetType", column = @Column(name = "unit_price_asset_type", nullable = false, length = 20)),
        @AttributeOverride(name = "currencyCode", column = @Column(name = "unit_price_currency", nullable = false, length = 10))
    })
    private Money unitPrice;

    @Embedded
    @AttributeOverrides({
        @AttributeOverride(name = "amount", column = @Column(name = "amount", nullable = false, precision = 36, scale = 18)),
        @AttributeOverride(name = "assetType", column = @Column(name = "amount_asset_type", nullable = false, length = 20)),
        @AttributeOverride(name = "currencyCode", column = @Column(name = "amount_currency", nullable = false, length = 10))
    })
    private Money amount;

    @Embedded
    @AttributeOverrides({
        @AttributeOverride(name = "amount", column = @Column(name = "realized_pnl", precision = 36, scale = 18)),
        @AttributeOverride(name = "assetType", column = @Column(name = "realized_pnl_asset_type", length = 20)),
        @AttributeOverride(name = "currencyCode", column = @Column(name = "realized_pnl_currency", length = 10))
    })
    private Money realizedPnl;

    @Column(name = "exchange_rate", precision = 19, scale = 6)
    private BigDecimal exchangeRate;

    private TransactionEntry(Transaction transaction, UUID accountId, EntryType entryType, String assetCode, 
                    Money quantity, Money unitPrice, BigDecimal exchangeRate, Money realizedPnl, String baseCurrencyCode) {
        this.transaction = transaction;
        this.accountId = accountId;
        this.entryType = entryType;
        this.assetCode = assetCode;
        
        this.quantity = quantity;
        this.unitPrice = unitPrice;
        this.exchangeRate = exchangeRate != null ? exchangeRate : BigDecimal.ONE;
        
        // 수량(BigDecimal) * 단가(Money) = 외화 가치(Money)
        Money valueBeforeExchange = this.unitPrice.multiply(this.quantity.getAmount());
        // 외화 가치(Money) * 환율(BigDecimal) = 최종 환산 가치(Money)
        Money finalCalculatedValue = valueBeforeExchange.multiply(this.exchangeRate);
                                    
        this.amount = Money.of(finalCalculatedValue.getAmount(), AssetType.FIAT, baseCurrencyCode);
        this.realizedPnl = realizedPnl;
    }

    public static TransactionEntry createBuyEntry(
            Transaction transaction, UUID accountId, String assetCode, 
            Money buyQuantity, Money buyPrice, BigDecimal exchangeRate, String baseCurrencyCode) {
        
        return new TransactionEntry(
                transaction, accountId, EntryType.DEBIT, assetCode, 
                buyQuantity, buyPrice, exchangeRate, 
                Money.zero(AssetType.FIAT, baseCurrencyCode),
                baseCurrencyCode
        );
    }

    public static TransactionEntry createSellEntry(
            Transaction transaction, UUID accountId, String assetCode, 
            Money sellQuantity, Money sellPrice, BigDecimal exchangeRate, Money averageCost, String baseCurrencyCode) {
        
        Money pnl = Money.zero(AssetType.FIAT, baseCurrencyCode);
        Money costPrice = sellPrice;
        
        if (averageCost != null) {
            pnl = sellPrice.subtract(averageCost).multiply(sellQuantity.getAmount());
            costPrice = averageCost;
        }
        
        return new TransactionEntry(
                transaction, accountId, EntryType.CREDIT, assetCode, 
                sellQuantity, 
                costPrice,
                exchangeRate, 
                pnl,
                baseCurrencyCode
        );
    }
}