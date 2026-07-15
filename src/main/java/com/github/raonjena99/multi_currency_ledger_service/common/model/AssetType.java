package com.github.raonjena99.multi_currency_ledger_service.common.model;

/**
 * 원장에서 관리하는 자산의 종류를 정의하는 AssetType(자산 타입) 열거형(Enum)입니다.
 * 각 자산은 고유한 소수점 스케일(Scale)을 가지며, 속성에 따라 다르게 처리됩니다.
 */
public enum AssetType {
    /** 법정화폐 (기본 소수점 4자리) */
    FIAT(4),         
    /** 주식 (기본 소수점 8자리) */
    STOCK(8),        
    /** 암호화폐 (기본 소수점 18자리) */
    CRYPTO(18),      
    /** 내부 포인트/마일리지 (소수점 없음) */
    POINT(0);        

    private final int defaultScale;

    /**
     * AssetType 생성자
     *
     * @param defaultScale 자산의 기본 소수점 자릿수
     */
    AssetType(int defaultScale) {
        this.defaultScale = defaultScale;
    }

    /**
     * 자산의 기본 소수점 자릿수를 반환합니다.
     *
     * @return 소수점 자릿수(Scale)
     */
    public int getDefaultScale() {
        return this.defaultScale;
    }

    /**
     * 온체인(On-chain) 트랜잭션 처리가 필요한 디지털 자산(암호화폐)인지 확인합니다.
     *
     * @return 디지털 자산일 경우 true, 그렇지 않으면 false
     */
    public boolean isDigitalAsset() {
        return this == CRYPTO;
    }

    /**
     * 자산이 더 이상 나눌 수 없는 단일 단위(소수점 0자리)인지 확인합니다.
     * 포인트(POINT)와 같이 분할 불가능한 자산을 식별하는 데 사용됩니다.
     *
     * @return 분할 불가능한 단일 단위 자산일 경우 true, 그렇지 않으면 false
     */
    public boolean isIndivisible() {
        return this.defaultScale == 0;
    }
}