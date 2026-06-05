package com.github.raonjena99.multi_currency_ledger_service.account.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

import com.github.raonjena99.multi_currency_ledger_service.common.domain.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;


@Entity
@Table(name = "account_balances")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AccountBalance extends BaseEntity{

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(name = "asset_code", nullable = false, length = 20)
    private String assetCode;

    @Column(nullable = false, precision = 36, scale = 18)
    private BigDecimal balance;

    @Column(name = "average_unit_price", nullable = false, precision = 36, scale = 18)
    private BigDecimal averageUnitPrice;

    @Version
    private Long version;

    // 매수
    public void addBalance(BigDecimal quantity, BigDecimal unitPrice){
        if (quantity.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Quantity must be positive");
        }

        BigDecimal totalCurrentValue = this.balance.multiply(this.averageUnitPrice);
        BigDecimal newAdditionValue = quantity.multiply(unitPrice);

        this.balance = this.balance.add(quantity);

        // 새로운 평균 단가 = (기존 총 가치 + 신규 매입 가치) / 총 수량
        this.averageUnitPrice = totalCurrentValue.add(newAdditionValue)
                .divide(this.balance, 18, RoundingMode.HALF_UP);
    }

    // 매도
    public BigDecimal subtractBalance(BigDecimal quantity) {
        if (quantity.compareTo(BigDecimal.ZERO) <= 0 || this.balance.compareTo(quantity) < 0) {
            throw new IllegalArgumentException("Invalid quantity or insufficient balance");
        }

        this.balance = this.balance.subtract(quantity);

        // 전량 매도 시 평균 단가 초기화
        if (this.balance.compareTo(BigDecimal.ZERO) == 0) {
            BigDecimal lastAveragePrice = this.averageUnitPrice;
            this.averageUnitPrice = BigDecimal.ZERO;
            return lastAveragePrice;
        }

        return this.averageUnitPrice;
    }

}
