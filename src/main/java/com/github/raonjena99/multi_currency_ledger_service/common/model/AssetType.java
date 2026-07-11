package com.github.raonjena99.multi_currency_ledger_service.common.model;

public enum AssetType {
    FIAT(4),         // 법정화폐
    STOCK(8),        // 주식
    CRYPTO(18),      // 암호화폐
    POINT(0);        // 내부 포인트/마일리지

    private final int defaultScale;

    AssetType(int defaultScale) {
        this.defaultScale = defaultScale;
    }

    public int getDefaultScale() {
        return this.defaultScale;
    }

    /**
     * 온체인 트랜잭션이 필요한 자산인지 식별
     */
    public boolean isDigitalAsset() {
        return this == CRYPTO;
    }

    /**
     * 단일 단위 자산인지 식별
     */
    public boolean isIndivisible() {
        return this.defaultScale == 0;
    }
}