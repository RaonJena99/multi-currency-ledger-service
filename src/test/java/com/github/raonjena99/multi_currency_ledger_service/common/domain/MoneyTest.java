package com.github.raonjena99.multi_currency_ledger_service.common.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import com.github.raonjena99.multi_currency_ledger_service.common.model.AssetType;

class MoneyTest {

    @Test
    void should_create_money_from_big_decimal() {
        Money money = Money.of(new BigDecimal("100.5"), AssetType.FIAT, "USD");
        assertThat(money.getAmount()).isEqualByComparingTo("100.50");
        assertThat(money.getAssetType()).isEqualTo(AssetType.FIAT);
        assertThat(money.getCurrencyCode()).isEqualTo("USD");
    }

    @Test
    void should_create_money_from_string() {
        Money money = Money.of("100.5", AssetType.FIAT, "USD");
        assertThat(money.getAmount()).isEqualByComparingTo("100.50");
    }

    @Test
    void should_create_zero_money() {
        Money money = Money.zero(AssetType.FIAT, "USD");
        assertThat(money.getAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(money.isZero()).isTrue();
    }

    @Test
    void should_throw_when_creating_with_null() {
        assertThatThrownBy(() -> Money.of((BigDecimal) null, AssetType.FIAT, "USD"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Money.of("10", null, "USD"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Money.of("10", AssetType.FIAT, null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void should_add_money() {
        Money m1 = Money.of("100", AssetType.FIAT, "USD");
        Money m2 = Money.of("50", AssetType.FIAT, "USD");
        Money result = m1.add(m2);
        assertThat(result.getAmount()).isEqualByComparingTo("150");
    }

    @Test
    void should_throw_when_adding_different_currencies() {
        Money m1 = Money.of("100", AssetType.FIAT, "USD");
        Money m2 = Money.of("50", AssetType.FIAT, "EUR");
        assertThatThrownBy(() -> m1.add(m2)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void should_subtract_money() {
        Money m1 = Money.of("100", AssetType.FIAT, "USD");
        Money m2 = Money.of("50", AssetType.FIAT, "USD");
        Money result = m1.subtract(m2);
        assertThat(result.getAmount()).isEqualByComparingTo("50");
    }

    @Test
    void should_multiply_money() {
        Money m1 = Money.of("100", AssetType.FIAT, "USD");
        Money result = m1.multiply(new BigDecimal("1.5"));
        assertThat(result.getAmount()).isEqualByComparingTo("150");
    }

    @Test
    void should_throw_when_multiply_null() {
        Money m1 = Money.of("100", AssetType.FIAT, "USD");
        assertThatThrownBy(() -> m1.multiply(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void should_divide_money() {
        Money m1 = Money.of("100", AssetType.FIAT, "USD");
        Money result = m1.divide(new BigDecimal("2"));
        assertThat(result.getAmount()).isEqualByComparingTo("50");
    }

    @Test
    void should_throw_when_divide_by_zero_or_null() {
        Money m1 = Money.of("100", AssetType.FIAT, "USD");
        assertThatThrownBy(() -> m1.divide(BigDecimal.ZERO)).isInstanceOf(ArithmeticException.class);
        assertThatThrownBy(() -> m1.divide(null)).isInstanceOf(ArithmeticException.class);
    }

    @Test
    void should_allocate_money() {
        Money m1 = Money.of("100", AssetType.FIAT, "USD");
        Money[] result = m1.allocate(3);
        assertThat(result).hasSize(3);
        assertThat(result[0].getAmount()).isEqualByComparingTo("33.34");
        assertThat(result[1].getAmount()).isEqualByComparingTo("33.33");
        assertThat(result[2].getAmount()).isEqualByComparingTo("33.33");
    }
    
    @Test
    void should_throw_when_allocate_less_than_one() {
        Money m1 = Money.of("100", AssetType.FIAT, "USD");
        assertThatThrownBy(() -> m1.allocate(0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void should_negate_money() {
        Money m1 = Money.of("100", AssetType.FIAT, "USD");
        assertThat(m1.negate().getAmount()).isEqualByComparingTo("-100");
    }

    @Test
    void isNegative_should_return_true_for_negative_amount() {
        Money m1 = Money.of("-10", AssetType.FIAT, "USD");
        assertThat(m1.isNegative()).isTrue();
        
        Money m2 = Money.of("10", AssetType.FIAT, "USD");
        assertThat(m2.isNegative()).isFalse();
    }

    @Test
    void compareTo_should_compare_correctly() {
        Money m1 = Money.of("100", AssetType.FIAT, "USD");
        Money m2 = Money.of("50", AssetType.FIAT, "USD");
        assertThat(m1.compareTo(m2)).isPositive();
        assertThat(m2.compareTo(m1)).isNegative();
        assertThat(m1.compareTo(m1)).isZero();
    }

    @Test
    void equals_and_hashCode_should_work() {
        Money m1 = Money.of("100", AssetType.FIAT, "USD");
        Money m2 = Money.of("100.00", AssetType.FIAT, "USD");
        Money m3 = Money.of("50", AssetType.FIAT, "USD");
        
        assertThat(m1).isEqualTo(m2);
        assertThat(m1.hashCode()).isEqualTo(m2.hashCode());
        assertThat(m1).isNotEqualTo(m3);
        assertThat(m1).isNotEqualTo(null);
        assertThat(m1).isNotEqualTo(new Object());
    }
    
    @Test
    void should_throw_on_equals_with_different_asset_type_but_same_currency() {
        Money m1 = Money.of("100", AssetType.FIAT, "KRW");
        Money m2 = Money.of("100", AssetType.CRYPTO, "KRW");
        assertThat(m1).isNotEqualTo(m2);
    }
}