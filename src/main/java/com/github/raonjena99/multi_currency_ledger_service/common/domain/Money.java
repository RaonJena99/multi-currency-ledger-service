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
 * 도메인 내 모든 금액 및 수량 계산을 책임지는 다중 통화 특화 Money(금액) 값 객체(Value Object)입니다.
 * 불변성(Immutability)을 보장하며, 산술 연산 시 자산 타입과 통화 코드의 일치 여부를 검증합니다.
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
        // 내부 생성 시점에 소수점 스케일 및 반올림 정규화를 항상 수행하여 데이터 일관성을 유지함
        this.amount = CurrencyScaleResolver.normalize(amount, this.assetType, this.currencyCode);
    }

    /**
     * BigDecimal 값을 사용하여 Money 객체를 생성합니다.
     *
     * @param amount       금액 (BigDecimal)
     * @param assetType    자산 타입
     * @param currencyCode 통화 코드
     * @return 생성된 Money 객체
     */
    public static Money of(BigDecimal amount, AssetType assetType, String currencyCode) {
        return new Money(amount, assetType, currencyCode);
    }

    /**
     * String 값을 사용하여 Money 객체를 생성합니다.
     *
     * @param amount       금액 (String)
     * @param assetType    자산 타입
     * @param currencyCode 통화 코드
     * @return 생성된 Money 객체
     */
    public static Money of(String amount, AssetType assetType, String currencyCode) {
        return new Money(new BigDecimal(amount), assetType, currencyCode);
    }

    /**
     * 금액이 0인 Money 객체를 생성합니다.
     *
     * @param assetType    자산 타입
     * @param currencyCode 통화 코드
     * @return 금액이 0으로 설정된 Money 객체
     */
    public static Money zero(AssetType assetType, String currencyCode) {
        return new Money(BigDecimal.ZERO, assetType, currencyCode);
    }

    /**
     * 두 Money 객체의 금액을 더합니다.
     *
     * @param other 더할 Money 객체
     * @return 두 금액의 합을 가진 새로운 Money 객체
     * @throws IllegalArgumentException 통화 코드가 일치하지 않을 경우
     */
    public Money add(Money other) {
        validateSameCurrency(other);
        return new Money(this.amount.add(other.amount), this.assetType, this.currencyCode);
    }

    /**
     * 현재 Money 객체에서 다른 Money 객체의 금액을 뺍니다.
     *
     * @param other 뺄 Money 객체
     * @return 두 금액의 차를 가진 새로운 Money 객체
     * @throws IllegalArgumentException 통화 코드가 일치하지 않을 경우
     */
    public Money subtract(Money other) {
        validateSameCurrency(other);
        return new Money(this.amount.subtract(other.amount), this.assetType, this.currencyCode);
    }

    /**
     * 금액에 특정 배율(Multiplier)을 곱합니다.
     *
     * @param multiplier 곱할 배율
     * @return 곱셈이 적용된 새로운 Money 객체
     * @throws IllegalArgumentException 배율이 null인 경우
     */
    public Money multiply(BigDecimal multiplier) {
        if (multiplier == null) throw new IllegalArgumentException("Multiplier cannot be null");
        return new Money(this.amount.multiply(multiplier), this.assetType, this.currencyCode);
    }

    /**
     * 금액을 특정 제수(Divisor)로 나눕니다.
     *
     * @param divisor 나눌 제수
     * @return 나눗셈이 적용된 새로운 Money 객체
     * @throws ArithmeticException 제수가 null이거나 0인 경우
     */
    public Money divide(BigDecimal divisor) {
        if (divisor == null || divisor.compareTo(BigDecimal.ZERO) == 0) {
            throw new ArithmeticException("Divisor cannot be null or zero");
        }
        // 중간 계산에서는 시스템 최대 정밀도(18자리)를 유지하여 데이터 손실을 방지하고, 
        // 반환 시점에 CurrencyScaleResolver를 통해 다시 정규화됨
        BigDecimal result = this.amount.divide(divisor, 18, RoundingMode.HALF_EVEN);
        return new Money(result, this.assetType, this.currencyCode);
    }

    /**
     * 금액을 여러 대상으로 분배합니다. 분배 시 발생하는 자투리 금액은 
     * 소수점 최소 단위(Minimum Unit)를 기준으로 앞쪽 대상부터 순차적으로 분배하여 총합을 맞춥니다.
     *
     * @param targets 분배할 대상의 수
     * @return 분배된 금액들을 담은 Money 배열
     * @throws IllegalArgumentException 대상의 수가 1보다 작을 경우
     */
    public Money[] allocate(int targets) {
        if (targets < 1) throw new IllegalArgumentException("Allocation targets must be at least 1");

        Money[] results = new Money[targets];
        
        int scale = CurrencyScaleResolver.resolveScale(this.assetType, this.currencyCode);
        
        // 해당 통화의 소수점 자릿수에 따른 최소 단위를 계산 (예: scale=2라면 0.01)
        BigDecimal minimumUnit = BigDecimal.ONE.movePointLeft(scale);

        // 균등하게 분배될 기본 금액을 소수점 버림(DOWN)으로 안전하게 계산
        BigDecimal targetBd = new BigDecimal(targets);
        BigDecimal lowResult = this.amount.divide(targetBd, scale, RoundingMode.DOWN);
        Money lowMoney = Money.of(lowResult, this.assetType, this.currencyCode);
        
        // 기본 금액에 최소 단위를 더해 자투리 금액을 받을 수 있는 금액(High) 생성
        Money highMoney = Money.of(lowResult.add(minimumUnit), this.assetType, this.currencyCode);

        // 총액에서 (기본 금액 * 대상 수)를 빼서 분배 후 남은 자투리 금액 산출
        BigDecimal remainder = this.amount.subtract(lowResult.multiply(targetBd));
        
        // 자투리 금액이 최소 단위 몇 개로 이루어져 있는지 몫을 구함 (이 개수만큼 1원씩 더 분배)
        int remainderCount = Math.abs(remainder.divide(minimumUnit, 0, RoundingMode.HALF_UP).intValue());

        // 앞쪽의 remainderCount 명에게는 +1단위(High)를 주고, 나머지는 기본(Low) 금액 할당
        for (int i = 0; i < targets; i++) {
            results[i] = i < remainderCount ? highMoney : lowMoney;
        }
        return results;
    }

    /**
     * 금액의 부호를 반전시킵니다.
     *
     * @return 부호가 반전된 새로운 Money 객체
     */
    public Money negate() {
        return new Money(this.amount.negate(), this.assetType, this.currencyCode);
    }
    
    /**
     * 금액이 음수인지 확인합니다.
     *
     * @return 음수일 경우 true, 그렇지 않으면 false
     */
    public boolean isNegative() {
        return this.amount.compareTo(BigDecimal.ZERO) < 0;
    }

    /**
     * 금액이 0인지 확인합니다.
     *
     * @return 금액이 0일 경우 true, 그렇지 않으면 false
     */
    public boolean isZero() {
        return this.amount.compareTo(BigDecimal.ZERO) == 0;
    }

    /**
     * 두 Money 객체의 크기를 비교합니다.
     *
     * @param other 비교할 Money 객체
     * @return 현재 금액이 더 크면 양수, 작으면 음수, 같으면 0
     * @throws IllegalArgumentException 통화 코드가 일치하지 않을 경우
     */
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
