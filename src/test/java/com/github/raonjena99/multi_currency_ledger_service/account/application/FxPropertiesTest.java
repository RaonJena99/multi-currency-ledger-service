package com.github.raonjena99.multi_currency_ledger_service.account.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.github.raonjena99.multi_currency_ledger_service.account.application.FxProperties.CurrencyTerms;

@DisplayName("환전 설정 검증")
class FxPropertiesTest {

    private static CurrencyTerms terms(String spread, String min, String max) {
        CurrencyTerms terms = new CurrencyTerms();
        terms.setSpread(new BigDecimal(spread));
        terms.setMinAmount(new BigDecimal(min));
        terms.setMaxAmount(new BigDecimal(max));
        return terms;
    }

    private static FxProperties valid() {
        FxProperties properties = new FxProperties();
        properties.getCurrencies().put("USD", terms("0.0175", "1", "10000"));
        return properties;
    }

    @Test
    @DisplayName("기본 설정은 유효하고, 통화 코드는 대소문자를 가리지 않고 찾는다")
    void validDefaultsAndCaseInsensitiveLookup() {
        FxProperties properties = valid();

        assertThatCode(properties::validate).doesNotThrowAnyException();
        assertThat(properties.getQuoteTtl()).isEqualTo(Duration.ofSeconds(30));
        assertThat(properties.termsOf("usd")).isPresent();
        assertThat(properties.termsOf("JPY")).as("설정에 없는 통화는 환전할 수 없다").isEmpty();
        assertThat(properties.termsOf(null)).isEmpty();
    }

    @Test
    @DisplayName("계좌에 우대율이 없으면 기본 우대율을 쓰고, 최대 우대율을 넘지 않게 자른다")
    void resolvesPreferentialRate() {
        FxProperties properties = valid();
        properties.setDefaultPreferentialRate(new BigDecimal("0.2"));
        properties.setMaxPreferentialRate(new BigDecimal("0.9"));

        assertThat(properties.resolvePreferentialRate(null)).isEqualByComparingTo("0.2");
        assertThat(properties.resolvePreferentialRate(new BigDecimal("0.5"))).isEqualByComparingTo("0.5");
        assertThat(properties.resolvePreferentialRate(BigDecimal.ZERO)).as("0 은 명시한 값이다")
                .isEqualByComparingTo("0");
        // 최대 우대율을 낮춘 뒤에도 이미 저장된 높은 값이 그대로 적용되면 안 된다.
        assertThat(properties.resolvePreferentialRate(new BigDecimal("0.95"))).isEqualByComparingTo("0.9");
    }

    @Test
    @DisplayName("스프레드는 0 이상 1 미만이어야 한다")
    void rejectsInvalidSpread() {
        FxProperties negative = valid();
        negative.getCurrencies().put("USD", terms("-0.01", "1", "10000"));
        FxProperties one = valid();
        one.getCurrencies().put("USD", terms("1", "1", "10000"));

        assertThatThrownBy(negative::validate).isInstanceOf(IllegalStateException.class).hasMessageContaining("USD");
        assertThatThrownBy(one::validate).isInstanceOf(IllegalStateException.class).hasMessageContaining("USD");
    }

    @Test
    @DisplayName("최소 금액은 0 보다 커야 하고 최대 금액은 최소 금액 이상이어야 한다")
    void rejectsInvalidLimits() {
        FxProperties zeroMin = valid();
        zeroMin.getCurrencies().put("USD", terms("0.0175", "0", "10000"));
        FxProperties inverted = valid();
        inverted.getCurrencies().put("USD", terms("0.0175", "100", "10"));

        assertThatThrownBy(zeroMin::validate).isInstanceOf(IllegalStateException.class).hasMessageContaining("USD");
        assertThatThrownBy(inverted::validate).isInstanceOf(IllegalStateException.class).hasMessageContaining("USD");
    }

    @Test
    @DisplayName("값이 빠진 통화 설정과 ISO 4217 이 아닌 통화 코드는 거부한다")
    void rejectsIncompleteOrUnknownCurrency() {
        FxProperties incomplete = valid();
        incomplete.getCurrencies().put("USD", new CurrencyTerms());
        FxProperties unknown = valid();
        unknown.getCurrencies().put("BTC", terms("0.0175", "1", "10"));

        assertThatThrownBy(incomplete::validate).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(unknown::validate).isInstanceOf(IllegalStateException.class).hasMessageContaining("BTC");
    }

    @Test
    @DisplayName("우대율 한도와 견적 유효 시간도 검증한다")
    void rejectsInvalidGlobalSettings() {
        FxProperties maxOne = valid();
        maxOne.setMaxPreferentialRate(BigDecimal.ONE);
        FxProperties defaultAboveMax = valid();
        defaultAboveMax.setMaxPreferentialRate(new BigDecimal("0.5"));
        defaultAboveMax.setDefaultPreferentialRate(new BigDecimal("0.6"));
        FxProperties zeroTtl = valid();
        zeroTtl.setQuoteTtl(Duration.ZERO);
        FxProperties longTtl = valid();
        longTtl.setQuoteTtl(Duration.ofMinutes(6));

        assertThatThrownBy(maxOne::validate).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(defaultAboveMax::validate).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(zeroTtl::validate).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(longTtl::validate).as("시세 허용 나이(5분)보다 오래 유효한 견적은 낡은 시세로 체결된다")
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("한도는 그 통화의 최소 단위로 표현할 수 있어야 한다 (USD 0.001 은 단위 미만, JPY 소수는 불가)")
    void rejectsLimitsFinerThanTheCurrencyUnit() {
        FxProperties finerMin = valid();
        finerMin.getCurrencies().put("USD", terms("0.0175", "0.001", "10000"));
        FxProperties finerMax = valid();
        finerMax.getCurrencies().put("USD", terms("0.0175", "1", "10000.005"));
        FxProperties fractionalYen = valid();
        fractionalYen.getCurrencies().put("JPY", terms("0.0175", "100.5", "1000000"));

        assertThatThrownBy(finerMin::validate).isInstanceOf(IllegalStateException.class).hasMessageContaining("USD");
        assertThatThrownBy(finerMax::validate).isInstanceOf(IllegalStateException.class).hasMessageContaining("USD");
        assertThatThrownBy(fractionalYen::validate).isInstanceOf(IllegalStateException.class).hasMessageContaining("JPY");
    }

    @Test
    @DisplayName("최소 단위까지의 한도는 허용한다 (USD 0.01, JPY 정수, 끝이 0 인 표기)")
    void allowsLimitsAtTheCurrencyUnit() {
        FxProperties properties = valid();
        properties.getCurrencies().put("USD", terms("0.0175", "0.01", "10000.00"));
        properties.getCurrencies().put("JPY", terms("0.0175", "100", "1500000"));

        assertThatCode(properties::validate).doesNotThrowAnyException();
    }
}
