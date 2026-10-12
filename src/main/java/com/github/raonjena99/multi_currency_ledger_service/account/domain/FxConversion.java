package com.github.raonjena99.multi_currency_ledger_service.account.domain;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.springframework.data.domain.Persistable;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 환전 체결 기록입니다. 견적의 조건과 회사 쪽 금액(시세 가치, 스프레드 수익)을 체결 때 그대로 남겨, 나중에 환율이 왜 그렇게
 * 정해졌는지 설명하는 감사 근거로 씁니다.
 *
 * <p>{@code id} 는 원장 거래 ID 와 같습니다. 견적 ID 는 유니크라서 한 견적은 한 번만 체결됩니다. 기록은 만들기만 하고 바꾸지
 * 않습니다.
 */
@Entity
@Table(name = "fx_conversions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FxConversion implements Persistable<UUID> {

    @Id
    private UUID id;

    @Column(name = "quote_id", nullable = false, updatable = false)
    private UUID quoteId;

    @Column(name = "account_id", nullable = false, updatable = false)
    private UUID accountId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private FxDirection direction;

    @Column(name = "from_currency", nullable = false, length = 10)
    private String fromCurrency;

    @Column(name = "from_amount", nullable = false, precision = 36, scale = 18)
    private BigDecimal fromAmount;

    @Column(name = "to_currency", nullable = false, length = 10)
    private String toCurrency;

    @Column(name = "to_amount", nullable = false, precision = 36, scale = 18)
    private BigDecimal toAmount;

    @Column(name = "base_currency", nullable = false, length = 10)
    private String baseCurrency;

    @Column(name = "market_rate", nullable = false, precision = 36, scale = 18)
    private BigDecimal marketRate;

    @Column(name = "customer_rate", nullable = false, precision = 36, scale = 18)
    private BigDecimal customerRate;

    @Column(name = "spread_rate", nullable = false, precision = 36, scale = 18)
    private BigDecimal spreadRate;

    @Column(name = "preferential_rate", nullable = false, precision = 5, scale = 4)
    private BigDecimal preferentialRate;

    /** 외화 금액의 시세 가치(기준 통화). 회사 FX 포지션이 이 금액으로 기록된다. */
    @Column(name = "market_value", nullable = false, precision = 36, scale = 18)
    private BigDecimal marketValue;

    /** 회사가 번 스프레드(기준 통화). 고객 금액과 시세 가치의 차이다. */
    @Column(name = "spread_revenue", nullable = false, precision = 36, scale = 18)
    private BigDecimal spreadRevenue;

    @Column(name = "converted_at", nullable = false)
    private OffsetDateTime convertedAt;

    private FxConversion(UUID id, FxQuote quote, BigDecimal marketValue, BigDecimal spreadRevenue,
                         OffsetDateTime convertedAt) {
        this.id = id;
        this.quoteId = quote.quoteId();
        this.accountId = quote.accountId();
        this.direction = quote.direction();
        this.fromCurrency = quote.fromCurrency();
        this.fromAmount = quote.fromAmount();
        this.toCurrency = quote.toCurrency();
        this.toAmount = quote.toAmount();
        this.baseCurrency = quote.baseCurrency();
        this.marketRate = quote.marketRate();
        this.customerRate = quote.customerRate();
        this.spreadRate = quote.spreadRate();
        this.preferentialRate = quote.preferentialRate();
        this.marketValue = marketValue;
        this.spreadRevenue = spreadRevenue;
        this.convertedAt = convertedAt;
    }

    /**
     * 견적을 체결한 기록을 만듭니다.
     *
     * @param id            원장 거래 ID. 체결이 발행하는 원장 기록 이벤트의 거래 ID 와 같다
     * @param quote         체결한 견적
     * @param marketValue   외화 금액의 시세 가치(기준 통화)
     * @param spreadRevenue 스프레드 수익(기준 통화)
     * @param convertedAt   체결 시각
     */
    public static FxConversion of(UUID id, FxQuote quote, BigDecimal marketValue, BigDecimal spreadRevenue,
                                  OffsetDateTime convertedAt) {
        return new FxConversion(id, quote, marketValue, spreadRevenue, convertedAt);
    }

    @Override
    public UUID getId() {
        return id;
    }

    @Override
    public boolean isNew() {
        // 체결 기록은 만들기만 하고 바꾸지 않는다. 항상 새 행이라고 알려 불필요한 조회 없이 곧바로 넣는다.
        return true;
    }
}
