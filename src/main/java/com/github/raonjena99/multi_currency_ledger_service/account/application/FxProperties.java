package com.github.raonjena99.multi_currency_ledger_service.account.application;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import com.github.raonjena99.multi_currency_ledger_service.common.domain.CurrencyScaleResolver;
import com.github.raonjena99.multi_currency_ledger_service.common.infrastructure.adapter.ExchangeRateCache;
import com.github.raonjena99.multi_currency_ledger_service.common.model.AssetType;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;

/**
 * 환전 설정입니다({@code ledger.fx}).
 *
 * <p><b>설정에 있는 통화만 환전할 수 있습니다.</b> ISO 4217 통화면 다 받는 방식은 쓰지 않습니다. 스프레드가
 * 정해지지 않은 통화를 0% 로 환전해 주면 회사가 수익 없이 환율 위험만 떠안기 때문입니다. 통화를 열 때는
 * 스프레드와 한도를 함께 정합니다.
 *
 * <p>잘못된 설정은 요청이 들어올 때가 아니라 기동할 때 실패시킵니다({@link #validate()}). 스프레드가 1 이상이면
 * 파는 쪽 환율이 0 이하가 되고, 한도가 거꾸로면 어떤 금액도 환전할 수 없습니다.
 */
@Component
@ConfigurationProperties(prefix = "ledger.fx")
@Getter
@Setter
public class FxProperties {

    /**
     * 견적 유효 시간. 시세 허용 나이({@link ExchangeRateCache#MAX_AGE})를 넘기면 안 됩니다. 견적을 받은 뒤에
     * 시장이 움직여도 견적 환율로 체결되므로, 오래 유효할수록 고객이 회사를 상대로 무료 옵션을 쥡니다.
     */
    private Duration quoteTtl = Duration.ofSeconds(30);

    /** 계좌에 우대율을 지정하지 않았을 때 적용하는 우대율. */
    private BigDecimal defaultPreferentialRate = BigDecimal.ZERO;

    /** 우대율 상한. 1(100% 우대)이면 스프레드 수익이 사라지므로 1 미만이어야 한다. */
    private BigDecimal maxPreferentialRate = new BigDecimal("0.9");

    /** 환전할 수 있는 외화(대문자 ISO 4217)별 조건. */
    private Map<String, CurrencyTerms> currencies = new LinkedHashMap<>();

    /**
     * 외화 한 종류의 환전 조건.
     *
     * <p>한도는 <b>외화 금액</b> 기준입니다. 기준 통화를 내고 외화를 사는 경우에는 받을 외화 금액에 적용됩니다.
     * 최소 금액은 반올림 때문에 스프레드 수익이 0 이 되는 초소액 환전을 막습니다.
     */
    @Getter
    @Setter
    public static class CurrencyTerms {
        /** 스프레드. 시세에 붙이는 비율(0.0175 = 1.75%). */
        private BigDecimal spread;
        /** 1회 최소 환전 금액 (외화 기준). */
        private BigDecimal minAmount;
        /** 1회 최대 환전 금액 (외화 기준). */
        private BigDecimal maxAmount;
    }

    /**
     * 외화의 환전 조건을 찾습니다.
     *
     * @param currency 외화 코드. 대소문자를 가리지 않는다
     * @return 설정에 없으면 비어 있다. 환전할 수 없는 통화다
     */
    public Optional<CurrencyTerms> termsOf(String currency) {
        if (currency == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(currencies.get(currency.trim().toUpperCase()));
    }

    /**
     * 계좌에 적용할 우대율을 정합니다. 계좌에 값이 없으면 기본 우대율을 쓰고, 상한을 넘으면 상한으로 자릅니다.
     *
     * <p>상한을 낮춘 뒤에도 이미 저장된 높은 값이 그대로 적용되어서는 안 되므로, 저장할 때뿐 아니라 쓸 때도 자릅니다.
     *
     * @param accountRate 계좌에 저장된 우대율. 지정하지 않았으면 null
     */
    public BigDecimal resolvePreferentialRate(BigDecimal accountRate) {
        BigDecimal rate = accountRate != null ? accountRate : defaultPreferentialRate;
        return rate.min(maxPreferentialRate);
    }

    /**
     * 설정이 유효한지 확인합니다. 기동 때 한 번 실행되어, 잘못된 설정이면 앱이 뜨지 않습니다.
     *
     * @throws IllegalStateException 설정이 유효하지 않은 경우
     */
    @PostConstruct
    public void validate() {
        if (quoteTtl == null || quoteTtl.isZero() || quoteTtl.isNegative()
                || quoteTtl.compareTo(ExchangeRateCache.MAX_AGE) > 0) {
            throw new IllegalStateException("ledger.fx.quote-ttl 은 0 보다 크고 시세 허용 나이("
                    + ExchangeRateCache.MAX_AGE.toMinutes() + "분) 이하여야 합니다: " + quoteTtl);
        }
        if (maxPreferentialRate == null || maxPreferentialRate.signum() < 0
                || maxPreferentialRate.compareTo(BigDecimal.ONE) >= 0) {
            throw new IllegalStateException(
                    "ledger.fx.max-preferential-rate 는 0 이상 1 미만이어야 합니다: " + maxPreferentialRate);
        }
        if (defaultPreferentialRate == null || defaultPreferentialRate.signum() < 0
                || defaultPreferentialRate.compareTo(maxPreferentialRate) > 0) {
            throw new IllegalStateException("ledger.fx.default-preferential-rate 는 0 이상이고 "
                    + "max-preferential-rate(" + maxPreferentialRate + ") 이하여야 합니다: " + defaultPreferentialRate);
        }
        currencies.forEach(FxProperties::validateCurrency);
    }

    private static void validateCurrency(String code, CurrencyTerms terms) {
        try {
            Currency.getInstance(code);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("ledger.fx.currencies 의 키가 ISO 4217 통화 코드가 아닙니다: " + code);
        }
        if (terms == null || terms.getSpread() == null || terms.getMinAmount() == null
                || terms.getMaxAmount() == null) {
            throw new IllegalStateException("ledger.fx.currencies." + code
                    + " 에는 spread, min-amount, max-amount 가 모두 필요합니다.");
        }
        if (terms.getSpread().signum() < 0 || terms.getSpread().compareTo(BigDecimal.ONE) >= 0) {
            throw new IllegalStateException("ledger.fx.currencies." + code
                    + ".spread 는 0 이상 1 미만이어야 합니다: " + terms.getSpread());
        }
        if (terms.getMinAmount().signum() <= 0 || terms.getMaxAmount().compareTo(terms.getMinAmount()) < 0) {
            throw new IllegalStateException("ledger.fx.currencies." + code
                    + " 의 min-amount 는 0 보다 크고 max-amount 는 min-amount 이상이어야 합니다: "
                    + terms.getMinAmount() + " ~ " + terms.getMaxAmount());
        }
        // 한도가 통화의 최소 단위보다 잘면(USD 0.001, JPY 100.5) 그 금액으로는 환전할 수 없으므로 오타일 가능성이 크다.
        int scale = CurrencyScaleResolver.resolveScale(AssetType.FIAT, code);
        if (terms.getMinAmount().stripTrailingZeros().scale() > scale
                || terms.getMaxAmount().stripTrailingZeros().scale() > scale) {
            throw new IllegalStateException("ledger.fx.currencies." + code
                    + " 의 한도는 " + code + " 의 최소 단위(소수 " + scale + "자리)로 표현할 수 있어야 합니다: "
                    + terms.getMinAmount() + " ~ " + terms.getMaxAmount());
        }
    }
}
