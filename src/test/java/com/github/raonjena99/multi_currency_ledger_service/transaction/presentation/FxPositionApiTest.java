package com.github.raonjena99.multi_currency_ledger_service.transaction.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.github.raonjena99.multi_currency_ledger_service.IntegrationTestSupport;
import com.github.raonjena99.multi_currency_ledger_service.account.domain.Account;
import com.github.raonjena99.multi_currency_ledger_service.account.infrastructure.AccountRepository;
import com.github.raonjena99.multi_currency_ledger_service.common.domain.Money;
import com.github.raonjena99.multi_currency_ledger_service.common.exception.MarketDataUnavailableException;
import com.github.raonjena99.multi_currency_ledger_service.common.model.AssetType;
import com.github.raonjena99.multi_currency_ledger_service.common.port.ExchangeRateProvider;
import com.github.raonjena99.multi_currency_ledger_service.common.port.ExchangeRateProvider.ExchangeRate;
import com.github.raonjena99.multi_currency_ledger_service.common.security.HeaderPrincipalResolver;
import com.github.raonjena99.multi_currency_ledger_service.transaction.application.LedgerService;
import com.github.raonjena99.multi_currency_ledger_service.transaction.application.command.FxConversionDetail;
import com.github.raonjena99.multi_currency_ledger_service.transaction.application.command.LedgerRecordingCommand;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 회사 외화 포지션 조회 API 를 보안 필터와 실제 DB 까지 포함한 컨텍스트로 검증합니다.
 *
 * <p>포지션은 회사의 위험 노출이라 관리자만 볼 수 있어야 합니다. 시세를 구하지 못한 통화가 있어도 나머지는 보여 줘야 합니다.
 */
@DisplayName("회사 외화 포지션 API")
class FxPositionApiTest extends IntegrationTestSupport {

    private static final String PATH = "/api/v1/admin/ledger/fx-positions";

    @Autowired private WebApplicationContext wac;
    @Autowired private LedgerService ledgerService;
    @Autowired private AccountRepository accountRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private JsonMapper jsonMapper;

    @MockitoBean private ExchangeRateProvider exchangeRateProvider;

    private MockMvc mockMvc;
    private final UUID accountId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity())
                .build();
        accountRepository.saveAndFlush(Account.open(accountId, "FX_POS_USER", "KRW"));
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.execute("TRUNCATE TABLE transaction_entries, transactions CASCADE");
        deleteTestAccounts();
    }

    private void customerBuysUsd() {
        // 1,322,750 원 → USD 1,000. 회사는 달러 1,000 을 시세 1,300,000 원에 내주고 스프레드 22,750 을 번다.
        ledgerService.recordDoubleEntry(new LedgerRecordingCommand(UUID.randomUUID(), accountId, "USD", AssetType.FIAT,
                "KRW", "KRW", "FX_CONVERSION", Money.of("1000", AssetType.FIAT, "USD"),
                new BigDecimal("1322750").divide(new BigDecimal("1000"), 18, RoundingMode.HALF_EVEN), BigDecimal.ONE,
                BigDecimal.ONE, null, false, OffsetDateTime.now(ZoneOffset.UTC),
                new FxConversionDetail("BUY_FOREIGN", new BigDecimal("1322750"), new BigDecimal("1300000"))));
    }

    private MockHttpServletRequestBuilder asAdmin() {
        return get(PATH).header(HeaderPrincipalResolver.SUBJECT_HEADER, "admin-1")
                .header(HeaderPrincipalResolver.ROLES_HEADER, "ADMIN");
    }

    private JsonNode fetch() throws Exception {
        String body = mockMvc.perform(asAdmin()).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return jsonMapper.readTree(body);
    }

    private static BigDecimal number(JsonNode node, String field) {
        return new BigDecimal(node.get(field).asString());
    }

    private static JsonNode positionOf(JsonNode book, String currency) {
        for (JsonNode position : book.get("positions")) {
            if (currency.equals(position.get("currency").asString())) {
                return position;
            }
        }
        throw new AssertionError("포지션이 없다: " + currency);
    }

    @Test
    @DisplayName("관리자는 통화별 순포지션과 현재 시세 재평가를 볼 수 있다")
    void adminSeesPositionsRevaluedAtTheCurrentRate() throws Exception {
        customerBuysUsd();
        when(exchangeRateProvider.getExchangeRate("USD", "KRW")).thenReturn(new ExchangeRate(new BigDecimal("1400"), false));

        JsonNode report = fetch();

        assertThat(report.get("asOf").asString()).isNotBlank();
        JsonNode krwBook = report.get("books").get(0);
        assertThat(krwBook.get("baseCurrency").asString()).isEqualTo("KRW");
        assertThat(number(krwBook, "spreadRevenue")).isEqualByComparingTo("22750");
        JsonNode usd = positionOf(krwBook, "USD");
        assertThat(number(usd, "netQuantity")).isEqualByComparingTo("-1000");
        assertThat(number(usd, "bookValue")).isEqualByComparingTo("-1300000");
        assertThat(number(usd, "marketRate")).isEqualByComparingTo("1400");
        assertThat(number(usd, "marketValue")).isEqualByComparingTo("-1400000");
        assertThat(number(usd, "fxResult")).isEqualByComparingTo("-100000");
        assertThat(usd.get("stale").asBoolean()).isFalse();
        assertThat(number(krwBook, "fxResult")).isEqualByComparingTo("-100000");
        assertThat(number(krwBook, "totalResult")).as("스프레드 22,750 − 환손실 100,000").isEqualByComparingTo("-77250");

        assertThat(report.get("exposures")).hasSize(2);
    }

    @Test
    @DisplayName("시세를 구하지 못한 통화가 있어도 200 이고 그 통화의 평가만 null 이다")
    void anUnavailableRateDoesNotFailTheWholeReport() throws Exception {
        customerBuysUsd();
        when(exchangeRateProvider.getExchangeRate("USD", "KRW")).thenThrow(new MarketDataUnavailableException("down"));

        JsonNode krwBook = fetch().get("books").get(0);

        JsonNode usd = positionOf(krwBook, "USD");
        assertThat(number(usd, "netQuantity")).isEqualByComparingTo("-1000");
        assertThat(usd.get("marketRate").isNull()).isTrue();
        assertThat(usd.get("marketValue").isNull()).isTrue();
        assertThat(usd.get("fxResult").isNull()).isTrue();
        assertThat(krwBook.get("totalResult").isNull()).isTrue();
        assertThat(number(krwBook, "spreadRevenue")).isEqualByComparingTo("22750");
    }

    @Test
    @DisplayName("환전이 없으면 비어 있다")
    void emptyWhenNothingWasConverted() throws Exception {
        JsonNode report = fetch();

        assertThat(report.get("books")).isEmpty();
        assertThat(report.get("exposures")).isEmpty();
    }

    @Test
    @DisplayName("계좌 소유자는 회사 포지션을 볼 수 없다 (403)")
    void ownersCannotSeeTheCompanyPosition() throws Exception {
        mockMvc.perform(get(PATH)
                        .header(HeaderPrincipalResolver.SUBJECT_HEADER, "user-1")
                        .header(HeaderPrincipalResolver.ACCOUNT_HEADER, accountId.toString()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("인증 없이는 볼 수 없다 (401)")
    void anonymousRequestsAreRejected() throws Exception {
        mockMvc.perform(get(PATH)).andExpect(status().isUnauthorized());
    }
}
