package com.github.raonjena99.multi_currency_ledger_service.account.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.github.raonjena99.multi_currency_ledger_service.account.application.FxProperties.CurrencyTerms;
import com.github.raonjena99.multi_currency_ledger_service.account.domain.Account;
import com.github.raonjena99.multi_currency_ledger_service.account.domain.FxDirection;
import com.github.raonjena99.multi_currency_ledger_service.account.domain.FxQuote;
import com.github.raonjena99.multi_currency_ledger_service.account.infrastructure.AccountRepository;
import com.github.raonjena99.multi_currency_ledger_service.common.exception.AccountNotFoundException;
import com.github.raonjena99.multi_currency_ledger_service.common.exception.ArbitrageRiskException;
import com.github.raonjena99.multi_currency_ledger_service.common.exception.FxAmountOutOfRangeException;
import com.github.raonjena99.multi_currency_ledger_service.common.exception.FxQuoteUnavailableException;
import com.github.raonjena99.multi_currency_ledger_service.common.exception.InvalidAccountStateException;
import com.github.raonjena99.multi_currency_ledger_service.common.exception.MarketDataUnavailableException;
import com.github.raonjena99.multi_currency_ledger_service.common.exception.UnsupportedAssetCodeException;
import com.github.raonjena99.multi_currency_ledger_service.common.exception.UnsupportedFxPairException;
import com.github.raonjena99.multi_currency_ledger_service.common.port.ExchangeRateProvider;
import com.github.raonjena99.multi_currency_ledger_service.common.port.ExchangeRateProvider.ExchangeRate;

@ExtendWith(MockitoExtension.class)
@DisplayName("환전 견적 서비스")
class FxQuoteServiceTest {

    @Mock private AccountRepository accountRepository;
    @Mock private ExchangeRateProvider exchangeRateProvider;

    private final FakeQuoteStore quoteStore = new FakeQuoteStore();
    private FxProperties properties;
    private FxQuoteService service;

    private final UUID accountId = UUID.randomUUID();

    /** 견적을 메모리에 담는 가짜 저장소. */
    private static class FakeQuoteStore implements FxQuoteStore {
        final Map<UUID, FxQuote> saved = new HashMap<>();
        Duration lastTtl;
        RuntimeException failure;

        @Override
        public void save(FxQuote quote, Duration ttl) {
            if (failure != null) {
                throw failure;
            }
            saved.put(quote.quoteId(), quote);
            lastTtl = ttl;
        }

        @Override
        public Optional<FxQuote> find(UUID quoteId) {
            return Optional.ofNullable(saved.get(quoteId));
        }
    }

    private static CurrencyTerms terms(String spread, String min, String max) {
        CurrencyTerms terms = new CurrencyTerms();
        terms.setSpread(new BigDecimal(spread));
        terms.setMinAmount(new BigDecimal(min));
        terms.setMaxAmount(new BigDecimal(max));
        return terms;
    }

    @BeforeEach
    void setUp() {
        properties = new FxProperties();
        properties.getCurrencies().put("USD", terms("0.0175", "1", "10000"));
        properties.getCurrencies().put("JPY", terms("0.0175", "100", "1000000"));
        service = new FxQuoteService(accountRepository, exchangeRateProvider, properties, quoteStore);
    }

    private Account krwAccount(String preferentialRate) {
        Account account = Account.open(accountId, "FX_USER", "KRW");
        if (preferentialRate != null) {
            account.changeFxPreferentialRate(new BigDecimal(preferentialRate));
        }
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));
        return account;
    }

    private void usdRate(String rate) {
        when(exchangeRateProvider.getExchangeRate("USD", "KRW"))
                .thenReturn(new ExchangeRate(new BigDecimal(rate), false));
    }

    @Test
    @DisplayName("원화를 내고 달러를 사는 견적: 환율 1,302.275 로 998.25 달러를 받는다")
    void quotesBuyingForeignCurrency() {
        krwAccount("0.9");
        usdRate("1300");

        OffsetDateTime before = OffsetDateTime.now();
        FxQuote quote = service.quote(accountId, "KRW", "USD", new BigDecimal("1300000"));

        assertThat(quote.direction()).isEqualTo(FxDirection.BUY_FOREIGN);
        assertThat(quote.accountId()).isEqualTo(accountId);
        assertThat(quote.fromCurrency()).isEqualTo("KRW");
        assertThat(quote.fromAmount()).isEqualByComparingTo("1300000");
        assertThat(quote.toCurrency()).isEqualTo("USD");
        assertThat(quote.toAmount()).isEqualByComparingTo("998.25");
        assertThat(quote.marketRate()).isEqualByComparingTo("1300");
        assertThat(quote.customerRate()).isEqualByComparingTo("1302.275");
        assertThat(quote.spreadRate()).isEqualByComparingTo("0.0175");
        assertThat(quote.preferentialRate()).isEqualByComparingTo("0.9");
        assertThat(quote.createdAt()).isAfterOrEqualTo(before);
        assertThat(quote.expiresAt()).isEqualTo(quote.createdAt().plus(Duration.ofSeconds(30)));
    }

    @Test
    @DisplayName("달러를 내고 원화를 받는 견적: 환율 1,297.725 로 1,297,725 원을 받는다")
    void quotesSellingForeignCurrency() {
        krwAccount("0.9");
        usdRate("1300");

        FxQuote quote = service.quote(accountId, "USD", "KRW", new BigDecimal("1000"));

        assertThat(quote.direction()).isEqualTo(FxDirection.SELL_FOREIGN);
        assertThat(quote.toAmount()).isEqualByComparingTo("1297725");
        assertThat(quote.customerRate()).isEqualByComparingTo("1297.725");
    }

    @Test
    @DisplayName("견적은 저장소에 유효 시간과 함께 저장되고, 호출마다 다른 ID 를 가진다")
    void savesTheQuoteWithItsTtl() {
        krwAccount(null);
        usdRate("1300");

        FxQuote first = service.quote(accountId, "KRW", "USD", new BigDecimal("1300000"));
        FxQuote second = service.quote(accountId, "KRW", "USD", new BigDecimal("1300000"));

        assertThat(quoteStore.saved).containsKeys(first.quoteId(), second.quoteId());
        assertThat(first.quoteId()).isNotEqualTo(second.quoteId());
        assertThat(quoteStore.lastTtl).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    @DisplayName("시세는 외화 → 기준 통화 방향으로 조회한다 (사고 팔 때 모두)")
    void looksUpTheRateFromForeignToBase() {
        krwAccount(null);
        usdRate("1300");

        service.quote(accountId, "KRW", "USD", new BigDecimal("1300000"));
        service.quote(accountId, "USD", "KRW", new BigDecimal("1000"));

        verify(exchangeRateProvider, org.mockito.Mockito.times(2)).getExchangeRate("USD", "KRW");
    }

    @Test
    @DisplayName("계좌에 우대율이 없으면 기본 우대율을 적용한다")
    void usesTheDefaultPreferentialRate() {
        properties.setDefaultPreferentialRate(new BigDecimal("0.2"));
        krwAccount(null);
        usdRate("1300");

        FxQuote quote = service.quote(accountId, "KRW", "USD", new BigDecimal("1300000"));

        assertThat(quote.preferentialRate()).isEqualByComparingTo("0.2");
        // 실효 스프레드 1.4% → 1300 × 1.014
        assertThat(quote.customerRate()).isEqualByComparingTo("1318.2");
    }

    @Test
    @DisplayName("계좌의 우대율이 설정 상한을 넘으면 상한으로 자른다")
    void clampsThePreferentialRateToTheMaximum() {
        properties.setMaxPreferentialRate(new BigDecimal("0.5"));
        krwAccount("0.9");
        usdRate("1300");

        FxQuote quote = service.quote(accountId, "KRW", "USD", new BigDecimal("1300000"));

        assertThat(quote.preferentialRate()).isEqualByComparingTo("0.5");
    }

    @Test
    @DisplayName("소수 자리가 없는 통화(JPY)도 견적을 낸다")
    void quotesZeroScaleCurrency() {
        krwAccount("0");
        when(exchangeRateProvider.getExchangeRate("JPY", "KRW"))
                .thenReturn(new ExchangeRate(new BigDecimal("9.5"), false));

        FxQuote quote = service.quote(accountId, "KRW", "JPY", new BigDecimal("100000"));

        assertThat(quote.toAmount()).isEqualByComparingTo("10345");
        assertThat(quote.toAmount().scale()).isZero();
    }

    @Test
    @DisplayName("통화 코드는 대소문자를 가리지 않는다")
    void normalizesCurrencyCodes() {
        krwAccount(null);
        usdRate("1300");

        FxQuote quote = service.quote(accountId, " krw ", "usd", new BigDecimal("1300000"));

        assertThat(quote.fromCurrency()).isEqualTo("KRW");
        assertThat(quote.toCurrency()).isEqualTo("USD");
    }

    @Test
    @DisplayName("기준 통화가 끼지 않은 환전(USD → JPY)은 지원하지 않는다")
    void rejectsPairsWithoutTheBaseCurrency() {
        krwAccount(null);

        assertThatThrownBy(() -> service.quote(accountId, "USD", "JPY", new BigDecimal("100")))
                .isInstanceOf(UnsupportedFxPairException.class)
                .hasMessageContaining("KRW");
        verifyNoInteractions(exchangeRateProvider);
    }

    @Test
    @DisplayName("설정에 없는 통화로는 환전할 수 없다")
    void rejectsCurrenciesWithoutTerms() {
        krwAccount(null);

        assertThatThrownBy(() -> service.quote(accountId, "KRW", "GBP", new BigDecimal("100000")))
                .isInstanceOf(UnsupportedFxPairException.class)
                .hasMessageContaining("GBP");
        verifyNoInteractions(exchangeRateProvider);
    }

    @Test
    @DisplayName("같은 통화끼리, 또는 ISO 4217 이 아닌 코드는 거부한다")
    void rejectsSameOrInvalidCurrencies() {
        assertThatThrownBy(() -> service.quote(accountId, "KRW", "KRW", new BigDecimal("1000")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.quote(accountId, "KRW", "BTC", new BigDecimal("1000")))
                .isInstanceOf(UnsupportedAssetCodeException.class);
        verifyNoInteractions(accountRepository, exchangeRateProvider);
    }

    @Test
    @DisplayName("없는 계좌는 AccountNotFoundException, 정지된 계좌는 InvalidAccountStateException")
    void rejectsUnknownAndInactiveAccounts() {
        when(accountRepository.findById(accountId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.quote(accountId, "KRW", "USD", new BigDecimal("1300000")))
                .isInstanceOf(AccountNotFoundException.class);

        Account suspended = Account.open(UUID.randomUUID(), "SUSPENDED", "KRW");
        suspended.suspend();
        UUID suspendedId = suspended.getId();
        when(accountRepository.findById(suspendedId)).thenReturn(Optional.of(suspended));
        assertThatThrownBy(() -> service.quote(suspendedId, "KRW", "USD", new BigDecimal("1300000")))
                .isInstanceOf(InvalidAccountStateException.class);
    }

    @Test
    @DisplayName("보내는 통화의 최소 단위보다 정밀한 금액은 거부한다")
    void rejectsAmountsMorePreciseThanTheCurrencyUnit() {
        krwAccount(null);

        assertThatThrownBy(() -> service.quote(accountId, "KRW", "USD", new BigDecimal("1300000.5")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.quote(accountId, "USD", "KRW", new BigDecimal("10.001")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(quoteStore.saved).isEmpty();
    }

    @Test
    @DisplayName("한도는 외화 금액 기준이다: 사는 쪽은 받을 외화, 파는 쪽은 내는 외화")
    void enforcesLimitsOnTheForeignAmount() {
        krwAccount("0.9");
        usdRate("1300");

        // 1,000 원 → USD 0.76 < 최소 1
        assertThatThrownBy(() -> service.quote(accountId, "KRW", "USD", new BigDecimal("1000")))
                .isInstanceOf(FxAmountOutOfRangeException.class);
        // 20,000,000 원 → USD 15,000+ > 최대 10,000
        assertThatThrownBy(() -> service.quote(accountId, "KRW", "USD", new BigDecimal("20000000")))
                .isInstanceOf(FxAmountOutOfRangeException.class);
        // USD 0.5 < 최소 1, USD 10,000.01 > 최대 10,000
        assertThatThrownBy(() -> service.quote(accountId, "USD", "KRW", new BigDecimal("0.5")))
                .isInstanceOf(FxAmountOutOfRangeException.class);
        assertThatThrownBy(() -> service.quote(accountId, "USD", "KRW", new BigDecimal("10000.01")))
                .isInstanceOf(FxAmountOutOfRangeException.class);
        assertThat(quoteStore.saved).isEmpty();
    }

    @Test
    @DisplayName("한도 경계값(최소·최대 그대로)은 허용한다")
    void allowsAmountsExactlyAtTheLimits() {
        krwAccount("0.9");
        usdRate("1300");

        assertThat(service.quote(accountId, "USD", "KRW", new BigDecimal("1")).toAmount()).isPositive();
        assertThat(service.quote(accountId, "USD", "KRW", new BigDecimal("10000")).toAmount()).isPositive();
    }

    @Test
    @DisplayName("낡은 시세(공급자 장애로 캐시를 쓴 경우)로는 견적을 내지 않는다")
    void rejectsStaleRates() {
        krwAccount(null);
        when(exchangeRateProvider.getExchangeRate("USD", "KRW"))
                .thenReturn(new ExchangeRate(new BigDecimal("1300"), true));

        assertThatThrownBy(() -> service.quote(accountId, "KRW", "USD", new BigDecimal("1300000")))
                .isInstanceOf(ArbitrageRiskException.class);
        assertThat(quoteStore.saved).isEmpty();
    }

    @Test
    @DisplayName("시세가 0 이하이면 견적을 내지 않는다")
    void rejectsNonPositiveRates() {
        krwAccount(null);
        when(exchangeRateProvider.getExchangeRate(anyString(), anyString()))
                .thenReturn(new ExchangeRate(BigDecimal.ZERO, false));

        assertThatThrownBy(() -> service.quote(accountId, "KRW", "USD", new BigDecimal("1300000")))
                .isInstanceOf(MarketDataUnavailableException.class);
    }

    @Test
    @DisplayName("견적을 저장하지 못하면 견적을 내주지 않고 예외를 그대로 올린다")
    void doesNotIssueAQuoteThatCouldNotBeStored() {
        krwAccount(null);
        usdRate("1300");
        quoteStore.failure = new FxQuoteUnavailableException("redis down", new RuntimeException());

        assertThatThrownBy(() -> service.quote(accountId, "KRW", "USD", new BigDecimal("1300000")))
                .isInstanceOf(FxQuoteUnavailableException.class);
    }
}
