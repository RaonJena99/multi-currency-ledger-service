package com.github.raonjena99.multi_currency_ledger_service.account.domain;

import java.util.UUID;

import com.github.raonjena99.multi_currency_ledger_service.common.domain.BaseEntity;
import com.github.raonjena99.multi_currency_ledger_service.common.domain.Money;
import com.github.raonjena99.multi_currency_ledger_service.common.model.AssetType;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(
    name = "monthly_account_ledgers",
    uniqueConstraints = {
        @UniqueConstraint(columnNames = {"account_id", "asset_code", "ledger_month"})
    }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MonthlyAccountLedger extends BaseEntity{
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(name = "asset_code", nullable = false, length = 20)
    private String assetCode;

    // 원장의 귀속 월
    @Column(name = "ledger_month", nullable = false, length = 7)
    private String ledgerMonth;

    @Embedded
    @AttributeOverrides({
        @AttributeOverride(name = "amount", column = @Column(name = "balance", nullable = false, precision = 36, scale = 18)),
        @AttributeOverride(name = "assetType", column = @Column(name = "asset_type", nullable = false, length = 20))
    })
    private Money balance;

    @Embedded
    @AttributeOverrides({
        @AttributeOverride(name = "amount", column = @Column(name = "average_unit_price", nullable = false, precision = 36, scale = 18)),
        @AttributeOverride(name = "assetType", column = @Column(name = "average_unit_price_asset_type", nullable = false, length = 20))
    })
    private Money averageUnitPrice;

    // 이전 월의 마감 잔액
    @Column(name = "carried_forward", nullable = false)
    private boolean carriedForward = false;

    @Version
    private Long version = 0L;

    // 최초 매수
    public MonthlyAccountLedger(UUID accountId, String assetCode, AssetType assetType, String ledgerMonth) {
        this.accountId = accountId;
        this.assetCode = assetCode;
        this.ledgerMonth = ledgerMonth;
        this.balance = Money.zero(assetType);
        this.averageUnitPrice = Money.zero(AssetType.FIAT);
        this.carriedForward = false;
    }

    // 이전 달 장부 -> 당월 장부
    public static MonthlyAccountLedger carryForwardFrom(MonthlyAccountLedger prev, String currentMonth) {
        MonthlyAccountLedger ledger = new MonthlyAccountLedger();
        ledger.accountId = prev.getAccountId();
        ledger.assetCode = prev.getAssetCode();
        ledger.ledgerMonth = currentMonth;
        ledger.balance = prev.getBalance();
        ledger.averageUnitPrice = prev.getAverageUnitPrice();
        ledger.carriedForward = true;
        return ledger;
    }

    // 매수
    public void addBalance(Money quantityToAdd, Money unitPrice) {
        // 유효성 검증
        if (quantityToAdd == null || quantityToAdd.isNegative() || quantityToAdd.isZero()) {
            throw new IllegalArgumentException("Quantity must be positive and non-null");
        }
        // 환율/단가 정보가 없는 경우 방어
        if (unitPrice == null || unitPrice.isNegative()) {
            throw new IllegalArgumentException("Unit price must be non-negative");
        }

        // 기존 총 가치 = 현재 수량 * 현재 평균단가
        Money totalCurrentValue = this.averageUnitPrice.multiply(this.balance.getAmount());
        
        // 신규 매입 가치 = 추가 수량 * 신규 단가
        Money newAdditionValue = unitPrice.multiply(quantityToAdd.getAmount());

        // 수량 업데이트
        this.balance = this.balance.add(quantityToAdd);

        // 새로운 평균 단가 = (기존 총 가치 + 신규 매입 가치) / 총 수량
        Money newTotalValue = totalCurrentValue.add(newAdditionValue);
        
        // Money 객체의 divide 가 AssetType 에 맞게 HALF_EVEN 으로 안전하게 처리함
        this.averageUnitPrice = newTotalValue.divide(this.balance.getAmount());
    }

    // 매도
    public Money subtractBalance(Money quantityToSubtract) {
        // 음수 or 공백
        if (quantityToSubtract.isNegative() || quantityToSubtract.isZero()) {
            throw new IllegalArgumentException("Invalid quantity to subtract");
        }
        
        // 잔고 부족
        if (this.balance.compareTo(quantityToSubtract) < 0) {
            throw new IllegalArgumentException("Insufficient balance");
        }
        
        // 매도
        this.balance = this.balance.subtract(quantityToSubtract);

        // 전량 매도 시 평균 단가 초기화
        if (this.balance.isZero()) {
            Money lastAveragePrice = this.averageUnitPrice;
            this.averageUnitPrice = Money.zero(this.averageUnitPrice.getAssetType());
            return lastAveragePrice;
        }

        return this.averageUnitPrice;
    }
}
