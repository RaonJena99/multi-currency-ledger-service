package com.github.raonjena99.multi_currency_ledger_service.common.infrastructure.adapter;

import java.math.BigDecimal;
import java.math.RoundingMode;

import org.springframework.stereotype.Component;

import com.github.raonjena99.multi_currency_ledger_service.common.port.ExchangeRateProvider;

import lombok.extern.slf4j.Slf4j;

/**
 * 개발 및 테스트 환경에서 환율 정보를 모의(Mock)로 제공하는 DummyExchangeRateAdapter(더미 환율 어댑터) 클래스입니다.
 * 외부 API 연동 없이 고정된 환율을 반환하여 시스템의 독립적인 테스트를 지원합니다.
 */
@Slf4j
@Component
public class DummyExchangeRateAdapter implements ExchangeRateProvider {
    
    /**
     * 기준 자산과 대상 자산 간의 모의 환율을 반환합니다.
     * BTC의 경우 고정된 법정화폐 비율(1억)을 사용하고, 그 외에는 1:1 비율을 반환합니다.
     *
     * @param baseAssetCode   기준 자산 코드
     * @param targetAssetCode 대상 자산 코드
     * @return 계산된 환율 정보를 담은 ExchangeRate(환율) 레코드
     */
    @Override
    public ExchangeRate getExchangeRate(String baseAssetCode, String targetAssetCode) {
        log.debug("Using dummy exchange rate for {} -> {}", baseAssetCode, targetAssetCode);
        
        if (baseAssetCode.equals(targetAssetCode)) {
            return new ExchangeRate(BigDecimal.ONE, false); 
        }

        // BTC와 법정화폐 간의 고정 환율 설정 (1 BTC = 1억)
        BigDecimal btcToFiatRate = new BigDecimal("100000000.00");

        if ("BTC".equals(targetAssetCode)) {
            BigDecimal rate = BigDecimal.ONE.divide(btcToFiatRate, 18, RoundingMode.HALF_EVEN);
            return new ExchangeRate(rate, false);
        } 
        else if ("BTC".equals(baseAssetCode)) {
            return new ExchangeRate(btcToFiatRate, false);
        }

        return new ExchangeRate(BigDecimal.ONE, false);
    }
}