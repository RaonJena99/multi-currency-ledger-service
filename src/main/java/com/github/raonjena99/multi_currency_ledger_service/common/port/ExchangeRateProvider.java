package com.github.raonjena99.multi_currency_ledger_service.common.port;

import java.math.BigDecimal;

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
     * 환율 조회 결과를 담는 ExchangeRate(환율) 레코드입니다.
     *
     * @param rate    조회된 환율
     * @param isStale 캐시 등에서 가져온 오래된(Stale) 데이터인지 여부
     */
    record ExchangeRate(BigDecimal rate, boolean isStale) {}
}
