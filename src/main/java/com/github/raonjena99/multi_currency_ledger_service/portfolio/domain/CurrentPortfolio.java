package com.github.raonjena99.multi_currency_ledger_service.portfolio.domain;

import java.math.BigDecimal;
import java.util.UUID;

import org.hibernate.annotations.Immutable;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Immutable
@Table(name = "current_portfolio_view")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CurrentPortfolio {
    @Id
    private String id;
    private UUID accountId;
    private String assetCode;
    private BigDecimal totalQuantity;
    private BigDecimal avgUnitPrice;
    private BigDecimal currentMarketPrice;
    private BigDecimal unrealizedPnl;
    private String lastUpdatedMonth;
}
