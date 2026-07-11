package com.github.raonjena99.multi_currency_ledger_service.portfolio.domain;

import java.math.BigDecimal;
import java.util.UUID;

import org.hibernate.annotations.Immutable;

import jakarta.persistence.Column;
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
    
    @Column(name = "account_id")
    private UUID accountId;
    
    @Column(name = "asset_code")
    private String assetCode;

    @Column(name = "balance_currency")
    private String balanceCurrency;

    @Column(name = "total_quantity")
    private BigDecimal totalQuantity;

    @Column(name = "quote_currency")
    private String quoteCurrency;

    @Column(name = "avg_unit_price")
    private BigDecimal avgUnitPrice;
    
    @Column(name = "current_market_price")
    private BigDecimal currentMarketPrice;
    
    @Column(name = "unrealized_pnl")
    private BigDecimal unrealizedPnl;
    
    @Column(name = "last_updated_month")
    private String lastUpdatedMonth;
}
