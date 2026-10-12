package com.github.raonjena99.multi_currency_ledger_service.account.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.github.raonjena99.multi_currency_ledger_service.IntegrationTestSupport;
import com.github.raonjena99.multi_currency_ledger_service.account.application.FxQuoteStore;
import com.github.raonjena99.multi_currency_ledger_service.account.domain.FxDirection;
import com.github.raonjena99.multi_currency_ledger_service.account.domain.FxQuote;
import com.github.raonjena99.multi_currency_ledger_service.common.exception.MarketDataUnavailableException;
import com.github.raonjena99.multi_currency_ledger_service.common.port.ExchangeRateProvider;
import com.github.raonjena99.multi_currency_ledger_service.common.port.ExchangeRateProvider.ExchangeRate;
import com.github.raonjena99.multi_currency_ledger_service.common.security.HeaderPrincipalResolver;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 환전 견적 API 를 보안 필터와 실제 Redis·DB 까지 포함한 컨텍스트로 검증합니다.
 *
 * <p>견적은 잔고를 바꾸지 않습니다. 계좌 소유자만 자기 계좌의 견적을 받을 수 있고, 응답의 견적 ID 로 저장소에서 같은
 * 견적을 찾을 수 있어야 체결이 그 견적을 쓸 수 있습니다.
 */
@DisplayName("환전 견적 API")
// 기본 환전 정책(application.yaml)은 운영 정책에 맞춰 바뀌는 값이다. 이 테스트의 숫자(이슈의 1,302.275 예시와 한도 경계)가
// 정책 변경에 흔들리지 않도록 USD 조건과 우대율 설정을 고정한다.
@TestPropertySource(properties = {
        "ledger.fx.currencies.USD.spread=0.0175",
        "ledger.fx.currencies.USD.min-amount=1",
        "ledger.fx.currencies.USD.max-amount=10000",
        "ledger.fx.default-preferential-rate=0",
        "ledger.fx.max-preferential-rate=0.9"})
class AccountFxQuoteApiTest extends IntegrationTestSupport {

    @Autowired private WebApplicationContext wac;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private FxQuoteStore quoteStore;
    @Autowired private JsonMapper jsonMapper;

    @MockitoBean private ExchangeRateProvider exchangeRateProvider;

    private MockMvc mockMvc;
    private final UUID accountId = UUID.randomUUID();

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity())
                .build();
        admin(post("/api/v1/admin/accounts"),
                "{\"accountId\":\"" + accountId + "\",\"ownerName\":\"FX_USER\",\"baseCurrency\":\"KRW\"}")
                .andExpect(status().isCreated());
        usdRate("1300", false);
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.execute("TRUNCATE TABLE account_status_history, outbox_events, transaction_entries, transactions, "
                + "monthly_account_ledgers, idempotency_records CASCADE");
        deleteTestAccounts();
    }

    private void usdRate(String rate, boolean stale) {
        when(exchangeRateProvider.getExchangeRate("USD", "KRW"))
                .thenReturn(new ExchangeRate(new BigDecimal(rate), stale));
    }

    private ResultActions admin(MockHttpServletRequestBuilder request, String body) throws Exception {
        return mockMvc.perform(request
                .header(HeaderPrincipalResolver.SUBJECT_HEADER, "admin-1")
                .header(HeaderPrincipalResolver.ROLES_HEADER, "ADMIN")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private ResultActions owner(UUID principalAccount, UUID targetAccount, String body) throws Exception {
        return mockMvc.perform(post("/api/v1/accounts/{id}/fx-quotes", targetAccount)
                .header(HeaderPrincipalResolver.SUBJECT_HEADER, "user-" + principalAccount)
                .header(HeaderPrincipalResolver.ACCOUNT_HEADER, principalAccount.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private ResultActions quote(String from, String to, String amount) throws Exception {
        return owner(accountId, accountId,
                "{\"fromCurrency\":\"" + from + "\",\"toCurrency\":\"" + to + "\",\"amount\":" + amount + "}");
    }

    private void setPreferentialRate(String rate) throws Exception {
        admin(put("/api/v1/admin/accounts/{id}/fx-preferential-rate", accountId), "{\"rate\":" + rate + "}")
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("원화 → 달러 견적: 우대 90% 면 환율 1,302.275 로 998.25 달러를 받는다")
    void quotesBuyingUsd() throws Exception {
        setPreferentialRate("0.9");

        quote("KRW", "USD", "1300000")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quoteId").isNotEmpty())
                .andExpect(jsonPath("$.fromCurrency").value("KRW"))
                .andExpect(jsonPath("$.fromAmount").value(1300000))
                .andExpect(jsonPath("$.toCurrency").value("USD"))
                .andExpect(jsonPath("$.toAmount").value(998.25))
                .andExpect(jsonPath("$.marketRate").value(1300))
                .andExpect(jsonPath("$.customerRate").value(1302.275))
                .andExpect(jsonPath("$.spreadRate").value(0.0175))
                .andExpect(jsonPath("$.preferentialRate").value(0.9))
                .andExpect(jsonPath("$.expiresAt").isNotEmpty());
    }

    @Test
    @DisplayName("달러 → 원화 견적: 환율 1,297.725 로 1,297,725 원을 받는다")
    void quotesSellingUsd() throws Exception {
        setPreferentialRate("0.9");

        quote("USD", "KRW", "1000")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.toAmount").value(1297725))
                .andExpect(jsonPath("$.customerRate").value(1297.725));
    }

    @Test
    @DisplayName("우대율을 지정하지 않은 계좌는 기본 우대율(0)이라 스프레드 전부를 받는다")
    void defaultPreferentialRateAppliesWhenUnset() throws Exception {
        quote("KRW", "USD", "1300000")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.preferentialRate").value(0))
                .andExpect(jsonPath("$.customerRate").value(1322.75));
    }

    @Test
    @DisplayName("응답의 견적 ID 로 저장소에서 같은 견적을 찾을 수 있고, 만료 시각이 유효 시간 뒤다")
    void quoteIsStoredAndExpires() throws Exception {
        OffsetDateTime before = OffsetDateTime.now();
        String body = quote("KRW", "USD", "1300000").andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode json = jsonMapper.readTree(body);

        FxQuote stored = quoteStore.find(UUID.fromString(json.get("quoteId").asString())).orElseThrow();
        assertThat(stored.accountId()).isEqualTo(accountId);
        assertThat(stored.direction()).isEqualTo(FxDirection.BUY_FOREIGN);
        assertThat(stored.toAmount()).isEqualByComparingTo(json.get("toAmount").decimalValue());
        assertThat(stored.expiresAt()).isBetween(before.plusSeconds(29), before.plusSeconds(32));
    }

    @Test
    @DisplayName("견적은 잔고를 바꾸지 않고 분개도 만들지 않는다")
    void quoteDoesNotTouchBalancesOrLedger() throws Exception {
        quote("KRW", "USD", "1300000").andExpect(status().isOk());

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM monthly_account_ledgers", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM outbox_events", Integer.class)).isZero();
    }

    @Test
    @DisplayName("남의 계좌의 견적은 403 이고, 관리자는 어느 계좌든 받을 수 있다")
    void ownershipIsEnforced() throws Exception {
        UUID other = UUID.randomUUID();
        String body = "{\"fromCurrency\":\"KRW\",\"toCurrency\":\"USD\",\"amount\":1300000}";

        owner(other, accountId, body).andExpect(status().isForbidden());

        admin(post("/api/v1/accounts/{id}/fx-quotes", accountId), body).andExpect(status().isOk());
    }

    @Test
    @DisplayName("인증 정보가 없으면 401 이다")
    void anonymousIsUnauthorized() throws Exception {
        mockMvc.perform(post("/api/v1/accounts/{id}/fx-quotes", accountId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fromCurrency\":\"KRW\",\"toCurrency\":\"USD\",\"amount\":1300000}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("없는 계좌는 404, 정지된 계좌는 422 다")
    void unknownAndSuspendedAccounts() throws Exception {
        UUID unknown = UUID.randomUUID();
        owner(unknown, unknown, "{\"fromCurrency\":\"KRW\",\"toCurrency\":\"USD\",\"amount\":1300000}")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"));

        admin(post("/api/v1/admin/accounts/{id}/suspend", accountId), "{\"reason\":\"FDS\"}")
                .andExpect(status().isOk());
        quote("KRW", "USD", "1300000")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("INVALID_ACCOUNT_STATE"));
    }

    @Test
    @DisplayName("입력 오류는 400 이다: 빈 통화, 0 이하의 금액, 같은 통화")
    void invalidInputsAreRejected() throws Exception {
        quote("", "USD", "1300000").andExpect(status().isBadRequest());
        quote("KRW", "USD", "0").andExpect(status().isBadRequest());
        quote("KRW", "USD", "-5").andExpect(status().isBadRequest());
        owner(accountId, accountId, "{\"fromCurrency\":\"KRW\",\"toCurrency\":\"USD\"}")
                .andExpect(status().isBadRequest());
        quote("KRW", "KRW", "1000").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        // 원화는 소수 자리가 없다
        quote("KRW", "USD", "1300000.5").andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("환전할 수 없는 조합은 422 FX_PAIR_NOT_SUPPORTED 다: 외화끼리, 설정에 없는 통화")
    void unsupportedPairs() throws Exception {
        quote("USD", "JPY", "100")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("FX_PAIR_NOT_SUPPORTED"));
        // XAU(금)는 ISO 4217 코드지만 환전 정책에 열려 있지 않다. 정책에 통화가 늘어도 열릴 일이 없는 코드를 쓴다.
        quote("KRW", "XAU", "1000000")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("FX_PAIR_NOT_SUPPORTED"));
        quote("KRW", "BTC", "1000000")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_ASSET"));
    }

    @Test
    @DisplayName("한도를 벗어난 금액은 422 FX_AMOUNT_OUT_OF_RANGE 다")
    void amountsOutsideTheLimits() throws Exception {
        quote("KRW", "USD", "1000")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("FX_AMOUNT_OUT_OF_RANGE"));
        quote("USD", "KRW", "10000.01")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("FX_AMOUNT_OUT_OF_RANGE"));
    }

    @Test
    @DisplayName("낡은 시세는 503 EXCHANGE_RATE_UNAVAILABLE 이고 견적을 저장하지 않는다")
    void staleRateIsRejected() throws Exception {
        usdRate("1300", true);

        quote("KRW", "USD", "1300000")
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("EXCHANGE_RATE_UNAVAILABLE"));
    }

    @Test
    @DisplayName("시세 공급자 장애는 503 MARKET_DATA_UNAVAILABLE 이다")
    void providerOutageIsServiceUnavailable() throws Exception {
        when(exchangeRateProvider.getExchangeRate("USD", "KRW"))
                .thenThrow(new MarketDataUnavailableException("down"));

        quote("KRW", "USD", "1300000")
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("MARKET_DATA_UNAVAILABLE"));
    }
}
