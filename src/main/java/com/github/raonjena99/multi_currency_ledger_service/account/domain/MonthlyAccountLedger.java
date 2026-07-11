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
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * MonthlyAccountLedger(월별 계좌 원장) 엔티티 클래스.
 * 특정 Account(계좌)의 특정 자산(Asset)에 대해 월 단위로 잔고와 평균 단가를 기록하고 관리합니다.
 */
@Entity
@Table(
    name = "monthly_account_ledgers",
    uniqueConstraints = {
        @UniqueConstraint(columnNames = {"account_id", "asset_code", "ledger_month"})
    }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MonthlyAccountLedger extends BaseEntity {
    
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "mal_seq")
    @SequenceGenerator(name = "mal_seq", sequenceName = "monthly_account_ledger_seq", allocationSize = 50)
    private Long id;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(name = "asset_code", nullable = false, length = 20)
    private String assetCode;

    // 원장의 귀속 월 (예: "2023-10")
    @Column(name = "ledger_month", nullable = false, length = 7)
    private String ledgerMonth;

    @Embedded
    @AttributeOverrides({
        @AttributeOverride(name = "amount", column = @Column(name = "balance", nullable = false, precision = 36, scale = 18)),
        @AttributeOverride(name = "assetType", column = @Column(name = "asset_type", nullable = false, length = 20)),
        @AttributeOverride(name = "currencyCode", column = @Column(name = "balance_currency", nullable = false, length = 10))
    })
    private Money balance;

    @Embedded
    @AttributeOverrides({
        @AttributeOverride(name = "amount", column = @Column(name = "average_unit_price", nullable = false, precision = 36, scale = 18)),
        @AttributeOverride(name = "assetType", column = @Column(name = "average_unit_price_asset_type", nullable = false, length = 20)),
        @AttributeOverride(name = "currencyCode", column = @Column(name = "average_unit_price_currency", nullable = false, length = 10))
    })
    private Money averageUnitPrice;

    // 이전 월의 마감 잔액 이월 여부
    @Column(name = "carried_forward", nullable = false)
    private boolean carriedForward = false;

    @Version
    private Long version = 0L;

    private MonthlyAccountLedger(UUID accountId, String assetCode, AssetType assetType, String ledgerMonth, String baseCurrency) {
        this.accountId = accountId;
        this.assetCode = assetCode;
        this.ledgerMonth = ledgerMonth;
        
        this.balance = Money.zero(assetType, assetCode);
        
        this.averageUnitPrice = Money.zero(AssetType.FIAT, baseCurrency);
        this.carriedForward = false;
    }

    /**
     * 특정 월에 대한 새로운 MonthlyAccountLedger(월별 계좌 원장)를 초기화합니다.
     *
     * @param accountId 계좌 ID
     * @param assetCode 자산 코드
     * @param assetType 자산 유형
     * @param ledgerMonth 귀속 월 (예: "2023-10")
     * @param baseCurrency 기준 통화 (예: "KRW")
     * @return 생성된 MonthlyAccountLedger(월별 계좌 원장) 객체
     */
    public static MonthlyAccountLedger initialize(UUID accountId, String assetCode, AssetType assetType, String ledgerMonth, String baseCurrency) {
        return new MonthlyAccountLedger(accountId, assetCode, assetType, ledgerMonth, baseCurrency);
    }

    /**
     * 이전 달의 MonthlyAccountLedger(월별 계좌 원장) 데이터를 기반으로 당월 장부로 이월(Carry-forward)합니다.
     *
     * @param prev 이전 달의 MonthlyAccountLedger 객체
     * @param currentMonth 당월 (예: "2023-11")
     * @return 이월 처리된 당월 MonthlyAccountLedger(월별 계좌 원장) 객체
     */
    public static MonthlyAccountLedger carryForwardFrom(MonthlyAccountLedger prev, String currentMonth) {
        MonthlyAccountLedger ledger = new MonthlyAccountLedger();
        ledger.accountId = prev.getAccountId();
        ledger.assetCode = prev.getAssetCode();
        ledger.ledgerMonth = currentMonth;
        
        // 이전 달의 잔고 및 평균 단가 정보를 그대로 당월로 복사 (이월 로직)
        ledger.balance = prev.getBalance();
        ledger.averageUnitPrice = prev.getAverageUnitPrice();
        ledger.carriedForward = true;
        
        return ledger;
    }

    /**
     * 자산 매수에 따른 잔고(Balance) 증가 및 평균 단가(Average Unit Price)를 갱신합니다.
     *
     * @param quantityToAdd 추가할 자산 수량
     * @param unitPrice 매입 단가
     * @throws IllegalArgumentException 추가할 수량이 null이거나 양수가 아닌 경우, 또는 단가가 음수인 경우
     */
    public void addBalance(Money quantityToAdd, Money unitPrice) {
        if (quantityToAdd == null || quantityToAdd.isNegative() || quantityToAdd.isZero()) {
            throw new IllegalArgumentException("Quantity must be positive and non-null");
        }
        if (unitPrice == null || unitPrice.isNegative()) {
            throw new IllegalArgumentException("Unit price must be non-negative");
        }

        // 기존 총 가치 계산 = 현재 평균단가(Money) * 현재 수량(BigDecimal)
        Money totalCurrentValue = this.averageUnitPrice.multiply(this.balance.getAmount());
        
        // 신규 매입 가치 계산 = 신규 단가(Money) * 추가 수량(BigDecimal)
        Money newAdditionValue = unitPrice.multiply(quantityToAdd.getAmount());

        // 매수한 수량만큼 잔고 업데이트
        this.balance = this.balance.add(quantityToAdd);

        // 새로운 총 가치 = 기존 총 가치 + 신규 매입 가치
        Money newTotalValue = totalCurrentValue.add(newAdditionValue);
        
        // 이동 평균법에 따른 새로운 평균 단가 갱신 = 총 가치 / 갱신된 총 수량
        this.averageUnitPrice = newTotalValue.divide(this.balance.getAmount());
    }

    /**
     * 자산 매도에 따른 잔고(Balance)를 감소시키고, 매도 시점의 평균 단가를 반환합니다.
     * 전량 매도시 평균 단가는 0으로 초기화됩니다.
     *
     * @param quantityToSubtract 매도할 자산 수량
     * @return 매도 시점의 평균 단가 (전량 매도의 경우 초기화 이전의 마지막 평균 단가 반환)
     * @throws IllegalArgumentException 차감할 수량이 null이거나 양수가 아닌 경우, 또는 잔고가 부족한 경우
     */
    public Money subtractBalance(Money quantityToSubtract) {
        if (quantityToSubtract == null || quantityToSubtract.isNegative() || quantityToSubtract.isZero()) {
            throw new IllegalArgumentException("Invalid quantity to subtract");
        }
        
        // 보유 잔고보다 매도 수량이 많은지 검증
        if (this.balance.compareTo(quantityToSubtract) < 0) {
            throw new IllegalArgumentException("Insufficient balance");
        }
        
        // 매도에 따른 수량 차감 처리
        this.balance = this.balance.subtract(quantityToSubtract);

        // 잔여 수량이 0이 되는 전량 매도의 경우, 평균 단가 초기화 필요
        if (this.balance.isZero()) {
            Money lastAveragePrice = this.averageUnitPrice;
            // 보유 자산이 없으므로 평균 단가 0으로 리셋
            this.averageUnitPrice = Money.zero(this.averageUnitPrice.getAssetType(), this.averageUnitPrice.getCurrencyCode());
            return lastAveragePrice;
        }

        return this.averageUnitPrice;
    }
}