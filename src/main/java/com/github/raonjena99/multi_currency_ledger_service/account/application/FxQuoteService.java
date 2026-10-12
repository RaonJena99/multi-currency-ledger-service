package com.github.raonjena99.multi_currency_ledger_service.account.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.github.raonjena99.multi_currency_ledger_service.account.application.FxProperties.CurrencyTerms;
import com.github.raonjena99.multi_currency_ledger_service.account.domain.Account;
import com.github.raonjena99.multi_currency_ledger_service.account.domain.FxDirection;
import com.github.raonjena99.multi_currency_ledger_service.account.domain.FxPricing;
import com.github.raonjena99.multi_currency_ledger_service.account.domain.FxPricing.Priced;
import com.github.raonjena99.multi_currency_ledger_service.account.domain.FxQuote;
import com.github.raonjena99.multi_currency_ledger_service.account.infrastructure.AccountRepository;
import com.github.raonjena99.multi_currency_ledger_service.common.domain.CurrencyScaleResolver;
import com.github.raonjena99.multi_currency_ledger_service.common.exception.AccountNotFoundException;
import com.github.raonjena99.multi_currency_ledger_service.common.exception.ArbitrageRiskException;
import com.github.raonjena99.multi_currency_ledger_service.common.exception.FxAmountOutOfRangeException;
import com.github.raonjena99.multi_currency_ledger_service.common.exception.InvalidAccountStateException;
import com.github.raonjena99.multi_currency_ledger_service.common.exception.MarketDataUnavailableException;
import com.github.raonjena99.multi_currency_ledger_service.common.exception.UnsupportedAssetCodeException;
import com.github.raonjena99.multi_currency_ledger_service.common.exception.UnsupportedFxPairException;
import com.github.raonjena99.multi_currency_ledger_service.common.model.AssetType;
import com.github.raonjena99.multi_currency_ledger_service.common.port.ExchangeRateProvider;
import com.github.raonjena99.multi_currency_ledger_service.common.port.ExchangeRateProvider.ExchangeRate;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 환전 견적을 냅니다. 시세에 스프레드와 우대율을 적용한 고객 환율과 받을 금액을 계산해 유효 시간 동안 저장합니다.
 *
 * <p>이 서비스는 트랜잭션을 열지 않습니다. 시세 조회는 외부 HTTP 호출일 수 있으므로 DB 커넥션을 잡은 채로 기다리면
 * 안 됩니다. 견적은 잔고를 바꾸지 않으며, 체결이 견적의 환율을 그대로 씁니다.
 *
 * <p>환전은 계좌의 기준 통화와 설정에 등록된 외화 사이에서만 합니다. 외화끼리는 기준 통화를 거쳐 두 번 환전합니다.
 * 이 제약 덕분에 시세는 항상 "외화 → 기준 통화" 한 방향이고, 평균 단가와 실현 손익도 기준 통화로 바로 계산됩니다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FxQuoteService {

    private final AccountRepository accountRepository;
    private final ExchangeRateProvider exchangeRateProvider;
    private final FxProperties fxProperties;
    private final FxQuoteStore quoteStore;

    /**
     * 환전 견적을 냅니다.
     *
     * @param accountId    견적을 받는 계좌
     * @param fromCurrency 고객이 내는 통화
     * @param toCurrency   고객이 받는 통화
     * @param amount       고객이 내는 금액. 내는 통화의 최소 단위보다 정밀하면 거부한다
     * @return 저장된 견적
     * @throws UnsupportedAssetCodeException  ISO 4217 통화가 아닌 경우
     * @throws IllegalArgumentException       같은 통화끼리이거나 금액이 최소 단위보다 정밀한 경우
     * @throws AccountNotFoundException       계좌가 없는 경우
     * @throws InvalidAccountStateException   정지·해지된 계좌인 경우
     * @throws UnsupportedFxPairException     기준 통화가 끼지 않았거나 설정에 없는 통화인 경우
     * @throws ArbitrageRiskException         시세가 낡은 경우 (공급자 장애로 캐시를 쓴 시세)
     * @throws MarketDataUnavailableException 시세가 유효하지 않은 경우
     * @throws FxAmountOutOfRangeException    외화 금액이 통화별 한도를 벗어난 경우
     */
    public FxQuote quote(UUID accountId, String fromCurrency, String toCurrency, BigDecimal amount) {
        String from = normalize(fromCurrency);
        String to = normalize(toCurrency);
        requireIsoCurrency(from);
        requireIsoCurrency(to);
        if (from.equals(to)) {
            throw new IllegalArgumentException("같은 통화끼리는 환전할 수 없습니다: " + from);
        }

        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new AccountNotFoundException(accountId));
        if (!account.isActive()) {
            throw new InvalidAccountStateException("Account is not active for FX conversion: " + accountId);
        }

        String base = account.getBaseCurrency();
        FxDirection direction;
        String foreign;
        if (from.equals(base)) {
            direction = FxDirection.BUY_FOREIGN;
            foreign = to;
        } else if (to.equals(base)) {
            direction = FxDirection.SELL_FOREIGN;
            foreign = from;
        } else {
            throw new UnsupportedFxPairException(String.format(
                    "환전은 계좌의 기준 통화(%s)와 외화 사이에서만 할 수 있습니다. 외화끼리는 기준 통화를 거쳐 두 번 환전하세요: %s → %s",
                    base, from, to));
        }
        CurrencyTerms terms = fxProperties.termsOf(foreign).orElseThrow(() -> new UnsupportedFxPairException(
                foreign + " 은(는) 환전할 수 없는 통화입니다."));

        int fromScale = CurrencyScaleResolver.resolveScale(AssetType.FIAT, from);
        if (amount.stripTrailingZeros().scale() > fromScale) {
            throw new IllegalArgumentException(String.format(
                    "금액 %s 이 %s 최소 단위(%s)보다 정밀합니다.", amount.toPlainString(), from,
                    CurrencyScaleResolver.minimumUnit(AssetType.FIAT, from).toPlainString()));
        }
        BigDecimal fromAmount = amount.setScale(fromScale, RoundingMode.UNNECESSARY);

        ExchangeRate marketRate = exchangeRateProvider.getExchangeRate(foreign, base);
        // 공급자 장애로 캐시를 쓴 시세로는 환전하지 않는다. 회사가 틀린 환율로 외화를 팔아 손해를 보는 일을 막는다.
        if (marketRate.isStale()) {
            throw new ArbitrageRiskException("시세가 지연 데이터라 환전 견적을 낼 수 없습니다: " + foreign + "/" + base);
        }
        if (marketRate.rate() == null || marketRate.rate().signum() <= 0) {
            throw new MarketDataUnavailableException("유효한 시세를 확보하지 못했습니다: " + foreign + "/" + base);
        }

        BigDecimal preferentialRate = fxProperties.resolvePreferentialRate(account.getFxPreferentialRate());
        int toScale = CurrencyScaleResolver.resolveScale(AssetType.FIAT, to);
        Priced priced = FxPricing.price(direction, fromAmount, marketRate.rate(), terms.getSpread(),
                preferentialRate, toScale);

        BigDecimal foreignAmount = direction == FxDirection.BUY_FOREIGN ? priced.receivedAmount() : fromAmount;
        requireWithinLimits(foreign, foreignAmount, terms);

        OffsetDateTime now = OffsetDateTime.now();
        FxQuote quote = new FxQuote(UUID.randomUUID(), accountId, direction, from, fromAmount, to,
                priced.receivedAmount(), marketRate.rate(), priced.customerRate(), terms.getSpread(),
                preferentialRate, now, now.plus(fxProperties.getQuoteTtl()));
        quoteStore.save(quote, fxProperties.getQuoteTtl());

        log.info("환전 견적을 냈습니다. quoteId={}, accountId={}, {} {} -> {} {}, rate={}", quote.quoteId(), accountId,
                from, fromAmount.toPlainString(), to, priced.receivedAmount().toPlainString(),
                priced.customerRate().toPlainString());
        return quote;
    }

    private void requireWithinLimits(String foreign, BigDecimal foreignAmount, CurrencyTerms terms) {
        if (foreignAmount.compareTo(terms.getMinAmount()) < 0 || foreignAmount.compareTo(terms.getMaxAmount()) > 0) {
            throw new FxAmountOutOfRangeException(String.format(
                    "%s 환전 금액 %s 이 한도(%s ~ %s)를 벗어났습니다.", foreign, foreignAmount.toPlainString(),
                    terms.getMinAmount().toPlainString(), terms.getMaxAmount().toPlainString()));
        }
    }

    private static void requireIsoCurrency(String code) {
        try {
            java.util.Currency.getInstance(code);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new UnsupportedAssetCodeException("환전 통화가 유효한 ISO 4217 코드가 아닙니다: " + code);
        }
    }

    private static String normalize(String currency) {
        return currency == null ? "" : currency.trim().toUpperCase();
    }
}
