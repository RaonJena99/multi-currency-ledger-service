package com.github.raonjena99.multi_currency_ledger_service.common.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.github.raonjena99.multi_currency_ledger_service.common.model.AssetType;

class MoneyTest {

    @Test
    @DisplayName("생성자 - null 파라미터 예외")
    void constructor_null() {
        assertThatThrownBy(() -> new Money(null, AssetType.FIAT))
            .isInstanceOf(IllegalArgumentException.class);
            
        assertThatThrownBy(() -> new Money(BigDecimal.TEN, null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("add/subtract - 다른 AssetType 예외")
    void typeMismatch_exception() {
        Money fiat = Money.of("10", AssetType.FIAT);
        Money crypto = Money.of("10", AssetType.CRYPTO);
        
        assertThatThrownBy(() -> fiat.add(crypto))
            .isInstanceOf(IllegalArgumentException.class);
            
        assertThatThrownBy(() -> fiat.subtract(crypto))
            .isInstanceOf(IllegalArgumentException.class);
            
        assertThatThrownBy(() -> fiat.compareTo(crypto))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("divide - 0으로 나누기 예외")
    void divideByZero_exception() {
        Money fiat = Money.of("10", AssetType.FIAT);
        
        assertThatThrownBy(() -> fiat.divide(BigDecimal.ZERO))
            .isInstanceOf(ArithmeticException.class);
    }
    
    @Test
    @DisplayName("divide - 정상 처리")
    void divide() {
        Money fiat = Money.of("10", AssetType.FIAT);
        Money result = fiat.divide(new BigDecimal("2"));
        assertThat(result.getAmount()).isEqualByComparingTo(new BigDecimal("5"));
    }
    
    @Test
    @DisplayName("negate - 정상 처리")
    void negate() {
        Money fiat = Money.of("10", AssetType.FIAT);
        Money result = fiat.negate();
        assertThat(result.isNegative()).isTrue();
        assertThat(result.getAmount()).isEqualByComparingTo(new BigDecimal("-10"));
    }

    @Test
    @DisplayName("isZero - 정상 처리")
    void isZero() {
        assertThat(Money.zero(AssetType.FIAT).isZero()).isTrue();
        assertThat(Money.of("0.00", AssetType.CRYPTO).isZero()).isTrue();
    }
    
    @Test
    @DisplayName("compareTo - 정상 처리")
    void compareTo() {
        Money m1 = Money.of("10", AssetType.FIAT);
        Money m2 = Money.of("20", AssetType.FIAT);
        
        assertThat(m1.compareTo(m2)).isNegative();
        assertThat(m2.compareTo(m1)).isPositive();
        assertThat(m1.compareTo(Money.of("10", AssetType.FIAT))).isZero();
    }
}
