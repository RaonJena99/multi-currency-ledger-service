package com.github.raonjena99.multi_currency_ledger_service.common.port;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 외부 환율 API 연동 및 시스템 복원력(Resilience4j) 적용을 위한 ExchangeRateProvider(환율 제공자) 포트(Port) 인터페이스입니다.
 */
public interface ExchangeRateProvider {
    /**
     * 두 자산 간의 환율을 조회합니다.
     *
     * @param baseAsset   기준 자산 코드
     * @param targetAsset 대상 자산 코드
     * @return 조회된 환율 정보를 담은 ExchangeRate 레코드
     */
    ExchangeRate getExchangeRate(String baseAsset, String targetAsset);

    /**
     * 여러 대상 자산의 환율을 일괄 조회합니다.
     * 기본 구현은 단건 조회를 순회하며, 구현체에서 multiGet 등으로 최적화할 수 있습니다.
     *
     * @param targetAssets 대상 자산 코드 목록
     * @param baseAsset 기준 자산 코드
     * @return 각 자산 코드별 환율 정보 Map
     */
    default Map<String, ExchangeRate> getExchangeRates(List<String> targetAssets, String baseCurrency) {
        java.util.Map<String, ExchangeRate> resultMap = new HashMap<>();
        for (String target : targetAssets) {
            resultMap.put(target, getExchangeRate(target, baseCurrency));
        }
        return resultMap;
    }

    /**
     * 환율 조회 결과를 담는 ExchangeRate(환율) 레코드입니다.
     *
     * @param rate    조회된 환율
     * @param isStale 캐시 등에서 가져온 오래된(Stale) 데이터인지 여부
     */
    record ExchangeRate(BigDecimal rate, boolean isStale) {}
}
