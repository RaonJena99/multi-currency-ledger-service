package com.github.raonjena99.multi_currency_ledger_service.transaction.domain;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.github.raonjena99.multi_currency_ledger_service.common.domain.Money;
import com.github.raonjena99.multi_currency_ledger_service.common.model.EntryType;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 다중 통화 거래를 기록하고 차변/대변의 복식부기 정합성을 검증하는 핵심 Aggregate Root
 */

@Entity
@Table(name = "transactions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Transaction implements org.springframework.data.domain.Persistable<UUID> {

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
    public void addBuyEntry(UUID accountId, String assetCode, Money quantity, Money unitPrice, BigDecimal exchangeRate) {
        TransactionEntry entry = TransactionEntry.createBuyEntry(this, accountId, assetCode, quantity, unitPrice, exchangeRate);
        this.entries.add(entry);
    }

    // 매도(대변) 분개 추가
    public void addSellEntry(UUID accountId, String assetCode, Money quantity, Money unitPrice, BigDecimal exchangeRate, Money averageCost) {
        TransactionEntry entry = TransactionEntry.createSellEntry(this, accountId, assetCode, quantity, unitPrice, exchangeRate, averageCost);
        this.entries.add(entry);
    }

    // 이종 자산 간 복식부기 정합성 검증
    private void verifyDoubleEntry() {
        BigDecimal totalDebit = BigDecimal.ZERO;
        BigDecimal totalCredit = BigDecimal.ZERO;

        for (TransactionEntry entry : entries) {
            // (수량 * 단가 * 환율) 연산을 마친 최종 기준 화폐(Fiat) 총액
            BigDecimal baseFiatValue = entry.getAmount().getAmount();

            if (entry.getEntryType() == EntryType.DEBIT) {
                totalDebit = totalDebit.add(baseFiatValue);
            } else if (entry.getEntryType() == EntryType.CREDIT) {
                totalCredit = totalCredit.add(baseFiatValue);
            }

            if (entry.getRealizedPnl() != null) {
                totalCredit = totalCredit.add(entry.getRealizedPnl().getAmount());
            }
        }

        // 환산된 가치 기준으로 차변과 대변 일치 여부 확인
        if (totalDebit.compareTo(totalCredit) != 0) {
            throw new IllegalStateException(
                String.format("Double-entry accounting error: Debits and Credits must balance in base fiat value. (Debit: %s, Credit: %s)", 
                totalDebit.toPlainString(), totalCredit.toPlainString())
            );
        }
    }

    @PrePersist
    @PreUpdate
    protected void onPersist() {
        verifyDoubleEntry();
    }

    @Override
    public boolean isNew() {
        return true;
    }
}
