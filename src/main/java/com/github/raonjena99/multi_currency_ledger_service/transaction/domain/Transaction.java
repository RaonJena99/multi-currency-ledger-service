package com.github.raonjena99.multi_currency_ledger_service.transaction.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.github.raonjena99.multi_currency_ledger_service.common.model.AssetType;
import com.github.raonjena99.multi_currency_ledger_service.common.model.EntryType;

@Entity
@Table(name = "transactions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Transaction {

    @Id
    private UUID id;

    @Column(name = "transaction_type", nullable = false, length = 30)
    private String transactionType;

    @Column(nullable = false, length = 255)
    private String description;

    @Column(name = "transacted_at", nullable = false, columnDefinition = "TIMESTAMPTZ")
    private OffsetDateTime transactedAt;

    @OneToMany(mappedBy = "transaction", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<TransactionEntry> entries = new ArrayList<>();

    public Transaction(UUID id, String transactionType, String description) {
        this.id = id;
        this.transactionType = transactionType;
        this.description = description;
        this.transactedAt = OffsetDateTime.now();
    }

    // 매수(차변) 분개 추가
    public void addBuyEntry(UUID accountId, String assetCode, AssetType assetType, 
                        BigDecimal quantity, BigDecimal unitPrice, BigDecimal exchangeRate) {
        TransactionEntry entry = TransactionEntry.createBuyEntry(this, accountId, assetCode, assetType, quantity, unitPrice, exchangeRate);
        this.entries.add(entry);
    }

    // 매도(대변) 분개 추가 메서드
    public void addSellEntry(UUID accountId, String assetCode, AssetType assetType, 
                        BigDecimal quantity, BigDecimal unitPrice, BigDecimal exchangeRate, BigDecimal averageCost) {
        TransactionEntry entry = TransactionEntry.createSellEntry(this, accountId, assetCode, assetType, quantity, unitPrice, exchangeRate, averageCost);
        this.entries.add(entry);
    }

    // 이종 자산 간 복식부기 정합성 검증
    public void verifyDoubleEntry() {
        BigDecimal totalDebit = BigDecimal.ZERO;
        BigDecimal totalCredit = BigDecimal.ZERO;

        // EntryType에 따라 차변과 대변을 분리하여 합산
        for (TransactionEntry entry : entries) {
            if (entry.getEntryType() == EntryType.DEBIT) {
                totalDebit = totalDebit.add(entry.getAmount());
            } else if (entry.getEntryType() == EntryType.CREDIT) {
                totalCredit = totalCredit.add(entry.getAmount());
            }
        }

        // 총 차변(Debit)과 총 대변(Credit)이 완벽히 일치해야 함
        if (totalDebit.compareTo(totalCredit) != 0) {
            throw new IllegalStateException(
                String.format("Double-entry accounting error: Debits and Credits must balance. (Debit: %s, Credit: %s)", 
                totalDebit.toPlainString(), totalCredit.toPlainString())
            );
        }
    }
}
