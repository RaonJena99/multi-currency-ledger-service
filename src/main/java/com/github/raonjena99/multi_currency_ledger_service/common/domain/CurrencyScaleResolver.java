package com.github.raonjena99.multi_currency_ledger_service.common.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.github.raonjena99.multi_currency_ledger_service.common.model.AssetType;

/**
 * 통화 종류(AssetType)와 통화 코드(currencyCode)를 기반으로 소수점 자릿수(Scale)를 동적으로 결정하고
 * 금액의 반올림 정규화를 수행하는 CurrencyScaleResolver(통화 스케일 리졸버) 유틸리티 클래스입니다.
 */
public class CurrencyScaleResolver {

    private static final Map<String, Integer> SCALE_CACHE = new ConcurrentHashMap<>();

    /**
     * 특정 AssetType(자산 타입)과 통화 코드에 맞는 완벽한 소수점 반올림을 수행합니다.
     * RoundingMode.HALF_EVEN을 사용하여 금융 계산의 오차를 최소화합니다.
     *
     * @param value        정규화할 금액(BigDecimal)
     * @param type         자산의 종류 (예: FIAT, CRYPTO)
     * @param currencyCode 자산의 식별 코드 (예: USD, BTC)
     * @return 소수점이 정규화된 금액. 입력값이 null인 경우 BigDecimal.ZERO 반환.
     */
    public static BigDecimal normalize(BigDecimal value, AssetType type, String currencyCode) {
        if (value == null) return BigDecimal.ZERO;
        
        int scale = resolveScale(type, currencyCode);
        // 계산 시 은행가 알고리즘(Banker's Rounding)인 HALF_EVEN을 적용하여 소수점 정규화 수행
        return value.setScale(scale, RoundingMode.HALF_EVEN);
    }

    /**
     * 특정 AssetType(자산 타입)과 통화 코드에 대한 소수점 자릿수(Scale)를 반환합니다.
     * 성능 최적화를 위해 메모리 내 캐싱을 활용하여 O(1) 시간 복잡도로 Scale을 조회합니다.
     *
     * @param type         자산의 종류
     * @param currencyCode 자산의 식별 코드
     * @return 해당 통화가 사용하는 소수점 자릿수
     */
    public static int resolveScale(AssetType type, String currencyCode) {
        if (type == null || currencyCode == null) {
            return 0;
        }
        
        String cacheKey = type.name() + ":" + currencyCode;
        // 동시성 환경에서도 안전하게 캐시에서 Scale 값을 조회하거나, 없으면 새로 계산하여 저장
        return SCALE_CACHE.computeIfAbsent(cacheKey, key -> calculateScale(type, currencyCode));
    }

    /**
     * 도메인 규칙에 따라 자산의 Scale을 동적으로 계산합니다.
     * FIAT(법정 화폐)인 경우 ISO 4217 표준을 기반으로 자릿수를 조회하며,
     * 그 외의 자산은 지정된 기본 Scale을 따릅니다.
     *
     * @param type         자산의 종류
     * @param currencyCode 자산의 식별 코드
     * @return 도메인 룰에 의해 계산된 소수점 자릿수
     */
    private static int calculateScale(AssetType type, String currencyCode) {
        if (type == AssetType.FIAT) {
            try {
                // ISO 4217 표준을 기반으로 해당 통화의 기본 소수점 자릿수를 가져옴
                int defaultFractionDigits = Currency.getInstance(currencyCode).getDefaultFractionDigits();
                // 일부 통화(예: 금속이나 기타 비표준 통화)는 -1을 반환하므로, 이에 대한 방어 로직 적용
                return defaultFractionDigits >= 0 ? defaultFractionDigits : type.getDefaultScale();
            } catch (IllegalArgumentException e) {
                // Java에 등록되지 않은 커스텀 FIAT 통화 코드가 들어올 경우 Fallback으로 기본 Scale 반환
                return type.getDefaultScale();
            }
        }
        
        // 주식이나 암호화폐 등 다른 자산 타입은 정의된 기본 Scale 값을 반환
        return type.getDefaultScale();
    }
}