package com.github.raonjena99.multi_currency_ledger_service.transaction.domain;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.data.domain.Persistable;

import com.github.raonjena99.multi_currency_ledger_service.common.domain.Money;
import com.github.raonjena99.multi_currency_ledger_service.common.exception.DoubleEntryImbalanceException;
import com.github.raonjena99.multi_currency_ledger_service.common.model.EntryType;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 다중 통화 거래를 기록하고 차변/대변의 복식부기 정합성을 검증하는 핵심 Transaction(트랜잭션) Aggregate Root 입니다.
 */

@Entity
@Table(name = "transactions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Transaction implements Persistable<UUID> {

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

    /**
     * 새로운 Transaction(트랜잭션) 엔티티를 생성하여 기록을 시작합니다.
     * @param id 트랜잭션 ID
     * @param transactionType 트랜잭션 유형
     * @param description 트랜잭션 설명
     * @return 생성된 Transaction(트랜잭션) 객체
     */
    public static Transaction record(UUID id, String transactionType, String description) {
        return new Transaction(id, transactionType, description);
    }

    /**
     * 트랜잭션에 차변(매수) 엔트리를 추가합니다.
     * @param accountId 계좌 ID
     * @param assetCode 자산 코드
     * @param quantity 수량
     * @param unitPrice 단가
     * @param exchangeRate 환율
     * @param baseCurrencyCode 기준 통화 코드
     */
    public void addBuyEntry(UUID accountId, String assetCode, Money quantity, Money unitPrice, BigDecimal exchangeRate, String baseCurrencyCode) {
        TransactionEntry entry = TransactionEntry.createBuyEntry(this, accountId, assetCode, quantity, unitPrice, exchangeRate, baseCurrencyCode);
        this.entries.add(entry);
    }

    /**
     * 트랜잭션에 대변(매도) 엔트리를 추가합니다.
     * @param accountId 계좌 ID
     * @param assetCode 자산 코드
     * @param quantity 수량
     * @param unitPrice 단가
     * @param exchangeRate 환율
     * @param averageCost 평균 단가
     * @param baseCurrencyCode 기준 통화 코드
     */
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

            // 대변에 가산하여 대차를 맞춤 (실현 손익이 존재하는 경우)
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
                throw new DoubleEntryImbalanceException(
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
                    throw new DoubleEntryImbalanceException(
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

    @Transient
    private boolean isNew = true;

    @PostPersist
    @PostLoad
    protected void markNotNew() {
        this.isNew = false;
    }

    /**
     * 엔티티가 새로운 상태인지 여부를 반환합니다.
     * @return isNew 필드 상태
     */
    @Override
    public boolean isNew() {
        return isNew;
    }
}
