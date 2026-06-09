package com.github.raonjena99.multi_currency_ledger_service.transaction.infrastructure.adapter;

import java.math.BigDecimal;
import java.math.RoundingMode;

import org.springframework.stereotype.Component;

import com.github.raonjena99.multi_currency_ledger_service.transaction.application.port.ExchangeRateProvider;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class DummyExchangeRateAdapter implements ExchangeRateProvider {
    @Override
    public BigDecimal getExchangeRate(String baseAssetCode, String targetAssetCode) {
        log.debug("Using dummy exchange rate for {} -> {}", baseAssetCode, targetAssetCode);
        
        if (baseAssetCode.equals(targetAssetCode)) {
            return BigDecimal.ONE;
        }

        BigDecimal btcToFiatRate = new BigDecimal("100000000.00");

        // 목표 자산이 BTC인 경우
        if ("BTC".equals(targetAssetCode)) {
            return BigDecimal.ONE.divide(btcToFiatRate, 18, RoundingMode.HALF_EVEN);
        } 
        // 기준 자산이 BTC인 경우
        else if ("BTC".equals(baseAssetCode)) {
            return btcToFiatRate;
        }

        return BigDecimal.ONE;
    }
}
