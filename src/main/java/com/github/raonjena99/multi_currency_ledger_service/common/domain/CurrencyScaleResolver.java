package com.github.raonjena99.multi_currency_ledger_service.common.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.github.raonjena99.multi_currency_ledger_service.common.model.AssetType;

public class CurrencyScaleResolver {

    private static final Map<String, Integer> SCALE_CACHE = new ConcurrentHashMap<>();

    /**
     * 특정 자산과 통화 코드에 맞는 완벽한 소수점 반올림을 수행
     */
    public static BigDecimal normalize(BigDecimal value, AssetType type, String currencyCode) {
        if (value == null) return BigDecimal.ZERO;
        
        int scale = resolveScale(type, currencyCode);
        return value.setScale(scale, RoundingMode.HALF_EVEN);
    }

    /**
     * O(1) 시간 복잡도로 Scale을 반환
     */
    public static int resolveScale(AssetType type, String currencyCode) {
        if (type == null || currencyCode == null) {
            return 0;
        }
        
        String cacheKey = type.name() + ":" + currencyCode;
        return SCALE_CACHE.computeIfAbsent(cacheKey, key -> calculateScale(type, currencyCode));
    }

    /**
     * 도메인 규칙에 따라 자산의 Scale을 동적으로 계산
     */
    private static int calculateScale(AssetType type, String currencyCode) {
        if (type == AssetType.FIAT) {
            try {
                // ISO 4217 표준 기반 동적 처리
                int defaultFractionDigits = Currency.getInstance(currencyCode).getDefaultFractionDigits();
                // 일부 통화(-1 반환)에 대한 방어 로직
                return defaultFractionDigits >= 0 ? defaultFractionDigits : type.getDefaultScale();
            } catch (IllegalArgumentException e) {
                // 커스텀 FIAT 통화 코드가 들어올 경우 Fallback
                return type.getDefaultScale();
            }
        }
        
        // 주식이나 암호화폐는 지정된 기본 Scale을 따름
        return type.getDefaultScale();
    }
}