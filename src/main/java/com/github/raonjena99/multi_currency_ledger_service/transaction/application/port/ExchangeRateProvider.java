package com.github.raonjena99.multi_currency_ledger_service.transaction.application.port;

import java.math.BigDecimal;

/**
 * 외부 환율 API 연동 및 Resilience4j 적용을 위한 포트 인터페이스
 */
public interface ExchangeRateProvider {
    BigDecimal getExchangeRate(String fromAssetCode, String toAssetCode);
}
