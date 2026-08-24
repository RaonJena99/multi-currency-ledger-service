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

/**
 * 원장의 개별 분개 항목을 나타내는 TransactionEntry(트랜잭션 엔트리) 엔티티입니다.
 */
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

    @Column(name = "unit_price", nullable = false, precision = 36, scale = 18)
    private BigDecimal unitPrice;

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

    @Column(name = "exchange_rate", precision = 36, scale = 18)
    private BigDecimal exchangeRate;

    /**
     * DB 컬럼(numeric(36,18))이 표현할 수 있는 최대 소수점 자릿수.
     * 자바 계산과 DB 저장값이 어긋나면 대차 검증과 CHECK 제약이 함께 깨지므로,
     * 계산에 들어가기 전에 자바 쪽에서 먼저 같은 스케일로 맞춘다.
     */
    private static final int DB_SCALE = 18;

    private static BigDecimal toDbScale(BigDecimal value) {
        return value.setScale(DB_SCALE, java.math.RoundingMode.HALF_EVEN);
    }

    private TransactionEntry(Transaction transaction, UUID accountId, EntryType entryType, String assetCode, 
                    Money quantity, BigDecimal unitPrice, BigDecimal exchangeRate, Money realizedPnl, String baseCurrencyCode) {
        this.transaction = transaction;
        this.accountId = accountId;
        this.entryType = entryType;
        this.assetCode = assetCode;
        
        this.quantity = quantity;
        // 자바 계산과 DB 저장값이 어긋나지 않도록 단가와 환율을 컬럼 스케일로 먼저 정규화한다.
        this.unitPrice = toDbScale(unitPrice);
        this.exchangeRate = toDbScale(exchangeRate != null ? exchangeRate : BigDecimal.ONE);

        // 수량 * 단가 (외화 기준)
        BigDecimal valueBeforeExchange = this.unitPrice.multiply(this.quantity.getAmount());

        // 단가가 기준 통화(Base Currency)와 같다면 exchangeRate는 이미 1로 넘어옵니다.
        // 따라서 분기 처리 없이 일괄적으로 exchangeRate를 곱해 원화 환산(Base Currency Value)을 수행합니다.
        BigDecimal finalCalculatedValue = valueBeforeExchange.multiply(this.exchangeRate);

        this.amount = Money.of(finalCalculatedValue, AssetType.FIAT, baseCurrencyCode);
        this.realizedPnl = realizedPnl;
    }

    /**
     * 차변(Debit)에 기록되는 매수(Buy) 엔트리를 생성합니다.
     * @param transaction 부모 Transaction(트랜잭션)
     * @param accountId 계좌 ID
     * @param assetCode 자산 코드
     * @param buyQuantity 매수 수량
     * @param buyPrice 매수 단가
     * @param exchangeRate 환율
     * @param baseCurrencyCode 기준 통화 코드
     * @return 생성된 차변 TransactionEntry(트랜잭션 엔트리) 객체
     */
    public static TransactionEntry createBuyEntry(
            Transaction transaction, UUID accountId, String assetCode, 
            Money buyQuantity, BigDecimal buyPrice, BigDecimal exchangeRate, String baseCurrencyCode) {
        
        return new TransactionEntry(
                transaction, accountId, EntryType.DEBIT, assetCode, 
                buyQuantity, buyPrice, exchangeRate, 
                Money.zero(AssetType.FIAT, baseCurrencyCode),
                baseCurrencyCode
        );
    }

    /**
     * 대변(Credit)에 기록되는 매도(Sell) 엔트리를 생성하고 실현 손익을 함께 계산합니다.
     *
     * <p><b>단위 규약이 이 메서드의 핵심입니다.</b>
     * <ul>
     *   <li>{@code sellPrice} — <b>결제 통화</b> 기준 단가. 생성자에서 {@code exchangeRate} 를 곱해
     *       기준 통화로 환산됩니다.</li>
     *   <li>{@code averageCostInBaseCurrency} — <b>기준 통화</b> 기준 평균 단가.
     *       {@code MonthlyAccountLedger.averageUnitPrice} 가 기준 통화로 저장되므로 그 단위를 따릅니다.</li>
     * </ul>
     *
     * <p>두 값의 단위가 다르므로 그냥 뺄 수 없습니다. 환산 없이 빼면 실현 손익과 처분 금액이
     * 환율 배수만큼 부풀려지고, 그런데도 <b>대차는 대수적으로 상쇄되어 정확히 일치</b>합니다.
     * ({@code amount + pnl = costPrice×qty×rate + (sellPrice−cost)×qty×rate = sellPrice×qty×rate})
     * 즉 {@code verifyDoubleEntry()} 로는 절대 검출되지 않습니다. 그래서 단위를 파라미터 이름에
     * 못박아 호출자가 실수할 여지를 줄였습니다.
     *
     * <p>{@code costPrice} 를 환율로 나누는 이유: 생성자가
     * {@code amount = unitPrice × quantity × exchangeRate} 로 계산하므로, 최종 {@code amount} 가
     * 기준 통화 평균 원가가 되도록 역산해야 합니다. 나눗셈 오차는
     * {@code quantity × 1e-18 × rate} 이하로 CHECK 제약의 허용 범위 안에 있습니다.
     *
     * @param transaction 부모 Transaction(트랜잭션)
     * @param accountId 계좌 ID
     * @param assetCode 자산 코드
     * @param sellQuantity 매도 수량
     * @param sellPrice 결제 통화 기준 매도 단가
     * @param exchangeRate 결제 통화 → 기준 통화 환율
     * @param averageCostInBaseCurrency <b>기준 통화</b> 기준 평균 매입 단가. null 이면 실현 손익 없음
     * @param baseCurrencyCode 기준 통화 코드
     * @return 생성된 대변 TransactionEntry(트랜잭션 엔트리) 객체
     */
    public static TransactionEntry createSellEntry(
            Transaction transaction, UUID accountId, String assetCode,
            Money sellQuantity, BigDecimal sellPrice, BigDecimal exchangeRate,
            BigDecimal averageCostInBaseCurrency, String baseCurrencyCode) {

        BigDecimal rate = exchangeRate != null ? exchangeRate : BigDecimal.ONE;

        BigDecimal pnlValue = BigDecimal.ZERO;
        BigDecimal costPrice = sellPrice;

        if (averageCostInBaseCurrency != null) {
            // 매도 단가를 기준 통화로 환산한 뒤 같은 단위끼리 뺀다.
            BigDecimal sellPriceInBase = sellPrice.multiply(rate);
            pnlValue = sellPriceInBase.subtract(averageCostInBaseCurrency)
                    .multiply(sellQuantity.getAmount());

            // 생성자가 rate 를 다시 곱하므로, amount 가 기준 통화 원가가 되도록 역산한다.
            costPrice = rate.compareTo(BigDecimal.ZERO) != 0
                    ? averageCostInBaseCurrency.divide(rate, DB_SCALE, java.math.RoundingMode.HALF_EVEN)
                    : averageCostInBaseCurrency;
        }

        Money pnl = Money.of(pnlValue, AssetType.FIAT, baseCurrencyCode);

        return new TransactionEntry(
                transaction, accountId, EntryType.CREDIT, assetCode,
                sellQuantity,
                costPrice,
                rate,
                pnl,
                baseCurrencyCode
        );
    }
}