package com.github.raonjena99.multi_currency_ledger_service.transaction.domain;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

    private Transaction(UUID id, String transactionType, String description) {
        this.id = id;
        this.transactionType = transactionType;
        this.description = description;
        this.transactedAt = OffsetDateTime.now();
    }

    // 기록
    public static Transaction record(UUID id, String transactionType, String description) {
        return new Transaction(id, transactionType, description);
    }

    // 매수(차변) 분개 추가
    public void addBuyEntry(UUID accountId, String assetCode, Money quantity, Money unitPrice, BigDecimal exchangeRate, String baseCurrencyCode) {
        TransactionEntry entry = TransactionEntry.createBuyEntry(this, accountId, assetCode, quantity, unitPrice, exchangeRate, baseCurrencyCode);
        this.entries.add(entry);
    }

    // 매도(대변) 분개 추가
    public void addSellEntry(UUID accountId, String assetCode, Money quantity, Money unitPrice, BigDecimal exchangeRate, Money averageCost, String baseCurrencyCode) {
        TransactionEntry entry = TransactionEntry.createSellEntry(this, accountId, assetCode, quantity, unitPrice, exchangeRate, averageCost, baseCurrencyCode);
        this.entries.add(entry);
    }

    // 이종 자산 간 복식부기 정합성 검증
    private void verifyDoubleEntry() {
        Map<String, BigDecimal> debitBalances = new HashMap<>();
        Map<String, BigDecimal> creditBalances = new HashMap<>();

        for (TransactionEntry entry : entries) {
            String currency = entry.getAmount().getCurrencyCode();
            BigDecimal baseFiatValue = entry.getAmount().getAmount();

            if (entry.getEntryType() == EntryType.DEBIT) {
                debitBalances.merge(currency, baseFiatValue, BigDecimal::add);
            } else if (entry.getEntryType() == EntryType.CREDIT) {
                creditBalances.merge(currency, baseFiatValue, BigDecimal::add);
            }

            // 대변에 가산하여 대차를 맞춤
            if (entry.getRealizedPnl() != null && !entry.getRealizedPnl().isZero()) {
                String pnlCurrency = entry.getRealizedPnl().getCurrencyCode();
                BigDecimal pnlValue = entry.getRealizedPnl().getAmount();
                creditBalances.merge(pnlCurrency, pnlValue, BigDecimal::add);
            }
        }

        // 모든 통화에 대해 차변 == 대변 검증
        for (String currency : debitBalances.keySet()) {
            BigDecimal debit = debitBalances.getOrDefault(currency, BigDecimal.ZERO);
            BigDecimal credit = creditBalances.getOrDefault(currency, BigDecimal.ZERO);
            
            if (debit.compareTo(credit) != 0) {
                throw new IllegalStateException(
                    String.format("Double-entry accounting error for currency [%s]: Debits and Credits must balance. (Debit: %s, Credit: %s)", 
                    currency, debit.toPlainString(), credit.toPlainString())
                );
            }
        }
        
        // 차변에는 없고 대변에만 존재하는 통화가 있는지도 교차 검증
        for (String currency : creditBalances.keySet()) {
            if (!debitBalances.containsKey(currency)) {
                BigDecimal credit = creditBalances.get(currency);
                if (credit.compareTo(BigDecimal.ZERO) != 0) {
                    throw new IllegalStateException(
                        String.format("Double-entry accounting error for currency [%s]: Credit exists without Debit. (Credit: %s)", 
                        currency, credit.toPlainString())
                    );
                }
            }
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
