package com.github.raonjena99.multi_currency_ledger_service.common.infrastructure.adapter;

import java.math.BigDecimal;
import java.math.RoundingMode;

import org.springframework.stereotype.Component;

import com.github.raonjena99.multi_currency_ledger_service.common.port.ExchangeRateProvider;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class DummyExchangeRateAdapter implements ExchangeRateProvider {
    
    @Override
    public ExchangeRate getExchangeRate(String baseAssetCode, String targetAssetCode) {
        log.debug("Using dummy exchange rate for {} -> {}", baseAssetCode, targetAssetCode);
        
        if (baseAssetCode.equals(targetAssetCode)) {
            return new ExchangeRate(BigDecimal.ONE, false); 
        }

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