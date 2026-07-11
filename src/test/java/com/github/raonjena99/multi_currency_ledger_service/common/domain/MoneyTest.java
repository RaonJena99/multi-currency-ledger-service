package com.github.raonjena99.multi_currency_ledger_service.common.domain;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MoneyTest {

    @Test
    @DisplayName("동일한 통화 간의 덧셈은 정상적으로 새로운 Money 객체를 반환한다.")
    void add_SameCurrency_ReturnsSum() {
        Money m1 = Money.of("100.50", "USD");
        Money m2 = Money.of("50.25", AssetType.USD);

        Money result = m1.add(m2);

        assertThat(result.getAmount()).isEqualByComparingTo("150.75");
        assertThat(result.getCurrency()).isEqualTo("USD");
    }

    @Test
    @DisplayName("이종 통화 간의 연산을 시도하면 예외가 발생하여 데이터 오염을 차단한다.")
    void add_DifferentCurrency_ThrowsException() {
        Money usd = Money.of(BigDecimal.valueOf(100), "USD");
        Money krw = Money.of(BigDecimal.valueOf(130000), "KRW");

        assertThatThrownBy(() -> usd.add(krw))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Currency mismatch");
    }

    @Test
    @DisplayName("음수 금액을 허용하지 않는 비즈니스 로직(필요시)을 확인한다.")
    void negativeMoney_ShouldBeHandledCorrectly() {
        Money base = Money.of(BigDecimal.valueOf(100), "KRW");
        Money subtract = Money.of(BigDecimal.valueOf(150), "KRW");

        Money result = base.subtract(subtract);

        assertThat(result.isNegative()).isTrue();
        assertThat(result.getAmount()).isEqualByComparingTo("-50");
    }
}
