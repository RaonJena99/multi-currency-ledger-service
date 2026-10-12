package com.github.raonjena99.multi_currency_ledger_service.account.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.github.raonjena99.multi_currency_ledger_service.account.application.FxProperties.CurrencyTerms;
import com.github.raonjena99.multi_currency_ledger_service.account.domain.FxDirection;
import com.github.raonjena99.multi_currency_ledger_service.account.domain.FxPricing;

/**
 * 이미지에 담겨 나가는 기본 환전 정책({@code application.yaml} 의 {@code ledger.fx})을 검증합니다.
 *
 * <p>스프레드와 한도는 회사의 수익과 위험을 정하는 운영 정책입니다. 값을 고칠 때 소수점을 한 칸 잘못 찍거나
 * (1.75 와 0.0175) 통화마다 단위가 다른 한도를 틀리게 적기 쉬워서, 기동 검증({@link FxProperties#validate()})이 못 잡는
 * 터무니없는 값을 여기서 막습니다. 정책 값 자체를 못 박지는 않습니다. 정책을 바꿀 때마다 테스트를 고치게 되기 때문입니다.
 */
@DisplayName("환전 기본 정책 (application.yaml)")
class FxDefaultPolicyTest {

    @EnableConfigurationProperties(FxProperties.class)
    static class Config {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(Config.class);

    private void withPolicy(java.util.function.Consumer<FxProperties> assertions) {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertions.accept(context.getBean(FxProperties.class));
        });
    }

    @Test
    @DisplayName("기본 정책은 기동 검증을 통과하고, 주요 통화와 원화가 열려 있다")
    void defaultPolicyStartsAndOpensTheMajorCurrencies() {
        withPolicy(policy -> {
            assertThat(policy.getCurrencies()).containsKeys("USD", "JPY", "EUR");
            // 계좌의 기준 통화가 원화가 아닌 경우에도 원화와 환전할 수 있어야 한다. 이때는 원화가 "외화"다.
            assertThat(policy.getCurrencies()).containsKey("KRW");
        });
    }

    @Test
    @DisplayName("스프레드는 0.1% ~ 10% 안이다 (0.0175 를 1.75 로 적는 오타를 막는다)")
    void spreadsAreWithinASaneBand() {
        withPolicy(policy -> policy.getCurrencies().forEach((code, terms) ->
                assertThat(terms.getSpread())
                        .as("%s 스프레드", code)
                        .isBetween(new BigDecimal("0.001"), new BigDecimal("0.10"))));
    }

    @Test
    @DisplayName("한도의 최대는 최소의 100배 이상이다 (한도를 거꾸로 적거나 단위를 틀리지 않았는지)")
    void limitsHaveARealRange() {
        withPolicy(policy -> policy.getCurrencies().forEach((code, terms) ->
                assertThat(terms.getMaxAmount())
                        .as("%s 최대 한도", code)
                        .isGreaterThanOrEqualTo(terms.getMinAmount().multiply(BigDecimal.valueOf(100)))));
    }

    @Test
    @DisplayName("우대율 기본값과 상한은 안전 범위다")
    void preferentialRatesAreSafe() {
        withPolicy(policy -> {
            assertThat(policy.getMaxPreferentialRate()).isLessThan(BigDecimal.ONE);
            assertThat(policy.getDefaultPreferentialRate()).isLessThanOrEqualTo(policy.getMaxPreferentialRate());
        });
    }

    @Test
    @DisplayName("모든 통화가 최소 금액과 최대 금액으로 실제 견적 계산을 통과한다")
    void everyCurrencyCanBePricedAtItsLimits() {
        withPolicy(policy -> {
            Map<String, CurrencyTerms> currencies = policy.getCurrencies();
            currencies.forEach((code, terms) -> {
                for (BigDecimal amount : new BigDecimal[] {terms.getMinAmount(), terms.getMaxAmount()}) {
                    // 외화를 파는 쪽: 낸 금액이 외화 금액이다. 시세는 임의의 양수(1)로 충분하다.
                    var priced = FxPricing.price(FxDirection.SELL_FOREIGN, amount, BigDecimal.ONE,
                            terms.getSpread(), policy.getMaxPreferentialRate(), 2);
                    assertThat(priced.customerRate()).as("%s 고객 환율", code).isPositive();
                }
            });
        });
    }
}
