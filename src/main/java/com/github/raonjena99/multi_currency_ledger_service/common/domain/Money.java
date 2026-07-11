package com.github.raonjena99.multi_currency_ledger_service.common.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

import com.github.raonjena99.multi_currency_ledger_service.common.model.AssetType;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 도메인 내 모든 금액/수량 계산을 책임지는 다중 통화 특화 Value Object
 */

@Embeddable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EqualsAndHashCode
public class Money {
    @Column(nullable = false, precision = 36, scale = 18)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AssetType assetType;

    @Column(nullable = false, length = 10)
    private String currencyCode;

    private Money(BigDecimal amount, AssetType assetType, String currencyCode) {
        if (amount == null || assetType == null || currencyCode == null) {
            throw new IllegalArgumentException("Amount, AssetType, and CurrencyCode cannot be null");
        }

        this.assetType = assetType;
        this.currencyCode = currencyCode.toUpperCase();
        this.amount = CurrencyScaleResolver.normalize(amount, this.assetType, this.currencyCode);
    }

    public static Money of(BigDecimal amount, AssetType assetType, String currencyCode) {
        return new Money(amount, assetType, currencyCode);
    }

    public static Money of(String amount, AssetType assetType, String currencyCode) {
        return new Money(new BigDecimal(amount), assetType, currencyCode);
    }

    public static Money zero(AssetType assetType, String currencyCode) {
        return new Money(BigDecimal.ZERO, assetType, currencyCode);
    }

    public Money add(Money other) {
        validateSameCurrency(other);
        return new Money(this.amount.add(other.amount), this.assetType, this.currencyCode);
    }

    public Money subtract(Money other) {
        validateSameCurrency(other);
        return new Money(this.amount.subtract(other.amount), this.assetType, this.currencyCode);
    }

    public Money multiply(BigDecimal multiplier) {
        if (multiplier == null) throw new IllegalArgumentException("Multiplier cannot be null");
        return new Money(this.amount.multiply(multiplier), this.assetType, this.currencyCode);
    }

    public Money divide(BigDecimal divisor) {
        if (divisor == null || divisor.compareTo(BigDecimal.ZERO) == 0) {
            throw new ArithmeticException("Divisor cannot be null or zero");
        }
        // 중간 단계에서는 최대한의 정밀도를 유지
        BigDecimal result = this.amount.divide(divisor, 18, RoundingMode.HALF_EVEN);
        return new Money(result, this.assetType, this.currencyCode);
    }

    public Money[] allocate(int targets) {
        if (targets < 1) throw new IllegalArgumentException("Allocation targets must be at least 1");

        Money[] results = new Money[targets];
        
        int scale = CurrencyScaleResolver.resolveScale(this.assetType, this.currencyCode);
        
        BigDecimal minimumUnit = BigDecimal.ONE.movePointLeft(scale);

        // 분배할 기본 금액 계산
        BigDecimal targetBd = new BigDecimal(targets);
        BigDecimal lowResult = this.amount.divide(targetBd, scale, RoundingMode.DOWN);
        Money lowMoney = Money.of(lowResult, this.assetType, this.currencyCode);
        Money highMoney = Money.of(lowResult.add(minimumUnit), this.assetType, this.currencyCode);

        // 자투리로 남은 금액 계산
        BigDecimal remainder = this.amount.subtract(lowResult.multiply(targetBd));
        int remainderCount = remainder.divide(minimumUnit, 0, RoundingMode.HALF_UP).intValue();

        for (int i = 0; i < targets; i++) {
            results[i] = i < remainderCount ? highMoney : lowMoney;
        }
        return results;
    }

    public Money negate() {
        return new Money(this.amount.negate(), this.assetType, this.currencyCode);
    }
    
    public boolean isNegative() {
        return this.amount.compareTo(BigDecimal.ZERO) < 0;
    }

    public boolean isZero() {
        return this.amount.compareTo(BigDecimal.ZERO) == 0;
    }

    public int compareTo(Money other) {
        validateSameCurrency(other);
        return this.amount.compareTo(other.amount);
    }

    private void validateSameCurrency(Money other) {
        if (this.assetType != other.assetType || !this.currencyCode.equals(other.currencyCode)) {
            throw new IllegalArgumentException(
                String.format("Currency mismatch: Expected [%s-%s] but got [%s-%s]", 
                    this.assetType, this.currencyCode, other.assetType, other.currencyCode)
            );
        }
    }
}
