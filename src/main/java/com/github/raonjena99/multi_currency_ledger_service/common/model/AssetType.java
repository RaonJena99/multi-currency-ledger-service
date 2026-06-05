package com.github.raonjena99.multi_currency_ledger_service.common.model;

import java.math.BigDecimal;
import java.math.RoundingMode;

public enum AssetType {
    FIAT(4),     // 법정화폐
    STOCK(0),    // 주식
    CRYPTO(18);  // 암호화폐

    private final int scale;

    AssetType(int scale) {
        this.scale = scale;
    }

    // HALF_EVEN 적용
    public BigDecimal normalize(BigDecimal value) {
        if (value == null) return BigDecimal.ZERO;
        return value.setScale(this.scale, RoundingMode.HALF_EVEN);
    }
}