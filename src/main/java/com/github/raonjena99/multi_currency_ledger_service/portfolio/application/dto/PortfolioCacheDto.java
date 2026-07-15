package com.github.raonjena99.multi_currency_ledger_service.portfolio.application.dto;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PortfolioCacheDto implements Serializable {
    private UUID accountId;
    private String baseCurrency;
    private List<AssetBalance> balances;
    
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AssetBalance implements Serializable {
        private String assetCode;
        private BigDecimal totalQuantity;
        private BigDecimal avgUnitPrice;
        private String quoteCurrency;
    }
}