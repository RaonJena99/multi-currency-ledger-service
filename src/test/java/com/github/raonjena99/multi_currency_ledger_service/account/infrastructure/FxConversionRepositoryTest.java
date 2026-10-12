package com.github.raonjena99.multi_currency_ledger_service.account.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import com.github.raonjena99.multi_currency_ledger_service.IntegrationTestSupport;
import com.github.raonjena99.multi_currency_ledger_service.account.domain.Account;
import com.github.raonjena99.multi_currency_ledger_service.account.domain.FxConversion;
import com.github.raonjena99.multi_currency_ledger_service.account.domain.FxDirection;
import com.github.raonjena99.multi_currency_ledger_service.account.domain.FxQuote;

/**
 * 환전 체결 기록의 저장과 제약을 실제 DB 로 검증합니다.
 *
 * <p>견적은 한 번만 쓸 수 있고, 그 보장은 {@code quote_id} 유니크 제약이 맡습니다. 견적을 Redis 에서 지우는 것은 그 뒤의
 * 정리일 뿐이라 지우기 전에 같은 견적으로 다시 체결을 시도해도 DB 가 막아야 합니다.
 */
@DisplayName("환전 체결 기록")
class FxConversionRepositoryTest extends IntegrationTestSupport {

    @Autowired private FxConversionRepository repository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private JdbcTemplate jdbc;

    @AfterEach
    void tearDown() {
        jdbc.execute("DELETE FROM fx_conversions");
        deleteTestAccounts();
    }

    private UUID account() {
        UUID id = UUID.randomUUID();
        accountRepository.saveAndFlush(Account.open(id, "FX_CONV_USER", "KRW"));
        return id;
    }

    /** 1,322,750 원 → USD 1,000 (시세 1,300 · 스프레드 1.75% · 우대 없음) 견적. */
    private FxQuote quote(UUID accountId) {
        OffsetDateTime now = OffsetDateTime.now();
        return new FxQuote(UUID.randomUUID(), accountId, FxDirection.BUY_FOREIGN, "KRW", new BigDecimal("1322750"),
                "USD", new BigDecimal("1000"), new BigDecimal("1300"), new BigDecimal("1322.75"),
                new BigDecimal("0.0175"), BigDecimal.ZERO, now, now.plusSeconds(30));
    }

    private FxConversion conversion(UUID accountId, FxQuote quote) {
        return FxConversion.of(UUID.randomUUID(), quote, new BigDecimal("1300000"), new BigDecimal("22750"),
                OffsetDateTime.now());
    }

    @Test
    @DisplayName("체결은 견적의 환율·금액과 시세 가치·스프레드 수익을 함께 저장한다")
    void storesTheQuoteTermsAndTheSpread() {
        UUID accountId = account();
        FxQuote quote = quote(accountId);
        FxConversion saved = repository.saveAndFlush(conversion(accountId, quote));

        FxConversion found = repository.findById(saved.getId()).orElseThrow();

        assertThat(found.getQuoteId()).isEqualTo(quote.quoteId());
        assertThat(found.getAccountId()).isEqualTo(accountId);
        assertThat(found.getDirection()).isEqualTo(FxDirection.BUY_FOREIGN);
        assertThat(found.getFromCurrency()).isEqualTo("KRW");
        assertThat(found.getFromAmount()).isEqualByComparingTo("1322750");
        assertThat(found.getToCurrency()).isEqualTo("USD");
        assertThat(found.getToAmount()).isEqualByComparingTo("1000");
        assertThat(found.getBaseCurrency()).isEqualTo("KRW");
        assertThat(found.getMarketRate()).isEqualByComparingTo("1300");
        assertThat(found.getCustomerRate()).isEqualByComparingTo("1322.75");
        assertThat(found.getSpreadRate()).isEqualByComparingTo("0.0175");
        assertThat(found.getPreferentialRate()).isEqualByComparingTo("0");
        assertThat(found.getMarketValue()).isEqualByComparingTo("1300000");
        assertThat(found.getSpreadRevenue()).isEqualByComparingTo("22750");
        assertThat(repository.findByQuoteId(quote.quoteId())).get().extracting(FxConversion::getId)
                .isEqualTo(saved.getId());
    }

    @Test
    @DisplayName("같은 견적으로 두 번 체결할 수 없다 (견적 ID 유니크)")
    void aQuoteCanOnlyBeConvertedOnce() {
        UUID accountId = account();
        FxQuote quote = quote(accountId);
        repository.saveAndFlush(conversion(accountId, quote));

        assertThatThrownBy(() -> repository.saveAndFlush(conversion(accountId, quote)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("없는 계좌의 체결은 저장할 수 없다")
    void theAccountMustExist() {
        FxQuote quote = quote(UUID.randomUUID());

        assertThatThrownBy(() -> repository.saveAndFlush(conversion(quote.accountId(), quote)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("DB 제약이 0 이하의 금액, 음수 스프레드 수익, 알 수 없는 방향을 막는다")
    void databaseConstraintsRejectNonsense() {
        UUID accountId = account();
        UUID id = UUID.randomUUID();
        String insert = "INSERT INTO fx_conversions (id, quote_id, account_id, direction, from_currency, from_amount, "
                + "to_currency, to_amount, base_currency, market_rate, customer_rate, spread_rate, preferential_rate, "
                + "market_value, spread_revenue, converted_at) VALUES (?, ?, ?, ?, 'KRW', ?, 'USD', ?, 'KRW', 1300, "
                + "1322.75, 0.0175, 0, 1300000, ?, now())";

        assertThatThrownBy(() -> jdbc.update(insert, id, UUID.randomUUID(), accountId, "BUY_FOREIGN",
                new BigDecimal("0"), new BigDecimal("1000"), new BigDecimal("22750")))
                .as("내는 금액 0").isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(insert, id, UUID.randomUUID(), accountId, "BUY_FOREIGN",
                new BigDecimal("1322750"), new BigDecimal("0"), new BigDecimal("22750")))
                .as("받는 금액 0").isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(insert, id, UUID.randomUUID(), accountId, "BUY_FOREIGN",
                new BigDecimal("1322750"), new BigDecimal("1000"), new BigDecimal("-1")))
                .as("음수 스프레드 수익").isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(insert, id, UUID.randomUUID(), accountId, "SIDEWAYS",
                new BigDecimal("1322750"), new BigDecimal("1000"), new BigDecimal("22750")))
                .as("알 수 없는 방향").isInstanceOf(DataIntegrityViolationException.class);
    }
}
