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
 * 도메인 내 모든 금액/수량 계산을 책임지는 Value Object
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

    public Money(BigDecimal amount, AssetType assetType) {
        if (amount == null || assetType == null) {
            throw new IllegalArgumentException("Amount and AssetType cannot be null");
        }
        this.assetType = assetType;
        
        this.amount = assetType.normalize(amount);
    }

    public static Money of(String amount, AssetType assetType) {
        return new Money(new BigDecimal(amount), assetType);
    }

    public static Money zero(AssetType assetType) {
        return new Money(BigDecimal.ZERO, assetType);
    }

    public Money add(Money other) {
        validateSameAssetType(other);
        return new Money(this.amount.add(other.amount), this.assetType);
    }

    public Money subtract(Money other) {
        validateSameAssetType(other);
        return new Money(this.amount.subtract(other.amount), this.assetType);
    }

    public Money multiply(BigDecimal multiplier) {
        return new Money(this.amount.multiply(multiplier), this.assetType);
    }

    public Money divide(BigDecimal divisor) {
        if (divisor.compareTo(BigDecimal.ZERO) == 0) {
            throw new ArithmeticException("Cannot divide by zero");
        }
        
        BigDecimal result = this.amount.divide(divisor, 18, RoundingMode.HALF_EVEN);
        return new Money(result, this.assetType);
    }

    public Money negate() {
        return new Money(this.amount.negate(), this.assetType);
    }
    
    public boolean isNegative() {
        return this.amount.compareTo(BigDecimal.ZERO) < 0;
    }

    public boolean isZero() {
        return this.amount.compareTo(BigDecimal.ZERO) == 0;
    }

    public int compareTo(Money other) {
        validateSameAssetType(other);
        return this.amount.compareTo(other.amount);
    }

    private void validateSameAssetType(Money other) {
        if (this.assetType != other.assetType) {
            throw new IllegalArgumentException(
                String.format("Currency mismatch: Expected %s but got %s", this.assetType, other.assetType)
            );
        }
    }
    
}
