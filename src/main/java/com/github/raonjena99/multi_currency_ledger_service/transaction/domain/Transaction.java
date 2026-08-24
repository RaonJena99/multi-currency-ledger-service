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

    private Transaction(UUID id, String transactionType, String description, OffsetDateTime transactedAt) {
        this.id = id;
        this.transactionType = transactionType;
        this.description = description;
        this.transactedAt = transactedAt != null ? transactedAt : OffsetDateTime.now();
    }

    /**
     * 새로운 Transaction(트랜잭션) 엔티티를 생성하여 기록을 시작합니다.
     * 거래 시각은 현재 시각으로 설정됩니다.
     *
     * @param id 트랜잭션 ID
     * @param transactionType 트랜잭션 유형
     * @param description 트랜잭션 설명
     * @return 생성된 Transaction(트랜잭션) 객체
     */
    public static Transaction record(UUID id, String transactionType, String description) {
        return new Transaction(id, transactionType, description, null);
    }

    /**
     * 거래 시각을 명시하여 Transaction(트랜잭션) 엔티티를 생성합니다.
     *
     * 원장 기록은 Kafka 소비 시점에 비동기로 이루어지므로, 시각을 주입하지 않으면
     * transacted_at 이 <b>소비 시각</b>이 되어 월차 원장의 귀속월과 어긋납니다.
     * 월 경계 근처에서는 잔고는 N월, 분개는 N+1월에 기록되는 문제가 생깁니다.
     *
     * @param id 트랜잭션 ID
     * @param transactionType 트랜잭션 유형
     * @param description 트랜잭션 설명
     * @param transactedAt 실제 거래가 발생한 시각. null 이면 현재 시각을 사용합니다.
     * @return 생성된 Transaction(트랜잭션) 객체
     */
    public static Transaction record(UUID id, String transactionType, String description, OffsetDateTime transactedAt) {
        return new Transaction(id, transactionType, description, transactedAt);
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
    public void addBuyEntry(UUID accountId, String assetCode, Money quantity, BigDecimal unitPrice, BigDecimal exchangeRate, String baseCurrencyCode) {
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
     * @param averageCostInBaseCurrency <b>기준 통화</b> 기준 평균 매입 단가
     * @param baseCurrencyCode 기준 통화 코드
     */
    public void addSellEntry(UUID accountId, String assetCode, Money quantity, BigDecimal unitPrice,
                             BigDecimal exchangeRate, BigDecimal averageCostInBaseCurrency, String baseCurrencyCode) {
        TransactionEntry entry = TransactionEntry.createSellEntry(this, accountId, assetCode, quantity,
                unitPrice, exchangeRate, averageCostInBaseCurrency, baseCurrencyCode);
        this.entries.add(entry);
    }

    /**
     * 이종 자산 간 복식부기 정합성(대차평균)을 검증합니다.
     *
     * 저장 직전에 애플리케이션에서 <b>명시적으로</b> 호출하십시오. JPA 라이프사이클 콜백만으로는
     * 부족합니다. {@code @PreUpdate} 는 부모 엔티티 행이 dirty 하지 않으면 발동하지 않으므로,
     * 자식 엔트리만 추가·변경된 경우 검증이 조용히 건너뛰어집니다.
     *
     * @throws DoubleEntryImbalanceException 어떤 통화에서든 차변과 대변이 일치하지 않을 경우
     */
    public void verifyDoubleEntry() {
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
