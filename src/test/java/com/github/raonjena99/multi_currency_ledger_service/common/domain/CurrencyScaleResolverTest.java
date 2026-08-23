package com.github.raonjena99.multi_currency_ledger_service.common.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import com.github.raonjena99.multi_currency_ledger_service.common.model.AssetType;

class CurrencyScaleResolverTest {

    @Test
    void normalize_should_return_zero_if_null() {
        BigDecimal result = CurrencyScaleResolver.normalize(null, AssetType.FIAT, "USD");
        assertThat(result).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void resolveScale_should_return_zero_if_null() {
        assertThat(CurrencyScaleResolver.resolveScale(null, "USD")).isEqualTo(0);
        assertThat(CurrencyScaleResolver.resolveScale(AssetType.FIAT, null)).isEqualTo(0);
    }

    @Test
    void resolveScale_should_handle_custom_fiat_gracefully() {
        // ZZZ is not a real ISO 4217 code, should fallback to default scale (e.g. 2)
        int scale = CurrencyScaleResolver.resolveScale(AssetType.FIAT, "ZZZ");
        assertThat(scale).isEqualTo(AssetType.FIAT.getDefaultScale());
    }

    @Test
    void normalize_should_scale_properly() {
        BigDecimal result = CurrencyScaleResolver.normalize(new BigDecimal("100.125"), AssetType.FIAT, "USD");
        // USD scale is 2, HALF_EVEN rounding for 100.125 -> 100.12
        assertThat(result).isEqualByComparingTo(new BigDecimal("100.12"));
        
        BigDecimal result2 = CurrencyScaleResolver.normalize(new BigDecimal("100.135"), AssetType.FIAT, "USD");
        // 100.135 -> 100.14
        assertThat(result2).isEqualByComparingTo(new BigDecimal("100.14"));
    }

    @Test
    void calculateScale_ShouldReturnDefaultScale_ForCustomFiat() {
        // "XYZ"와 같은 Java Currency에 등록되지 않은 임의의 FIAT 코드
        int scale = CurrencyScaleResolver.resolveScale(AssetType.FIAT, "XYZ");
        // FIAT의 기본 Scale인 4가 반환되어야 함
        org.junit.jupiter.api.Assertions.assertEquals(4, scale);
    }
    
    @Test
    void should_cover_private_constructor() throws Exception {
        java.lang.reflect.Constructor<CurrencyScaleResolver> constructor = CurrencyScaleResolver.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        constructor.newInstance();
    }

    @Test
    void resolveScale_should_return_default_for_non_fiat() {
        int scale = CurrencyScaleResolver.resolveScale(AssetType.CRYPTO, "BTC");
        assertThat(scale).isEqualTo(AssetType.CRYPTO.getDefaultScale());
    }

    @Test
    void resolveScale_should_handle_fiat_with_negative_scale() {
        // Pseudo currency XSU might return -1 for default fraction digits, or we can use a known one if any. 
        // Or if it throws exception, it falls to catch block.
        // Let's test reflection or a known currency like "XAU" (Gold) or "XDR" which might have -1
        // Actually, if we just want to hit `defaultFractionDigits >= 0 ? defaultFractionDigits : type.getDefaultScale()`
        // The catch block already hits `type.getDefaultScale()`. 
        // We can just rely on the fallback block, but to hit `< 0` branch, we need a currency with -1.
        // E.g. "XAU" has -1 in Java Currency class.
        int scale = CurrencyScaleResolver.resolveScale(AssetType.FIAT, "XAU");
        assertThat(scale).isEqualTo(AssetType.FIAT.getDefaultScale());
    }
}
