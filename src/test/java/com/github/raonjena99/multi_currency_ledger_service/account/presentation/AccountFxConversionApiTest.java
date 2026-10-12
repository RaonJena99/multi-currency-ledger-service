package com.github.raonjena99.multi_currency_ledger_service.account.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

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
import com.github.raonjena99.multi_currency_ledger_service.account.domain.FxQuote;
import com.github.raonjena99.multi_currency_ledger_service.common.port.ExchangeRateProvider;
import com.github.raonjena99.multi_currency_ledger_service.common.port.ExchangeRateProvider.ExchangeRate;
import com.github.raonjena99.multi_currency_ledger_service.common.security.HeaderPrincipalResolver;

import tools.jackson.databind.json.JsonMapper;

/**
 * 환전 체결 API 를 보안 필터와 실제 Redis·DB 까지 포함한 컨텍스트로 검증합니다.
 *
 * <p>견적은 한 번만 체결되고, 체결가는 견적에 고정되며, 같은 멱등성 키의 재전송은 처음 체결을 돌려받습니다. 체결이 실패하면
 * 잔고와 견적은 그대로여야 합니다.
 */
@DisplayName("환전 체결 API")
// 기본 환전 정책(application.yaml)은 운영 정책에 맞춰 바뀌는 값이다. 이 테스트의 숫자가 정책 변경에 흔들리지 않도록
// USD 조건과 우대율 설정을 고정한다.
@TestPropertySource(properties = {
        "ledger.fx.currencies.USD.spread=0.0175",
        "ledger.fx.currencies.USD.min-amount=1",
        "ledger.fx.currencies.USD.max-amount=10000",
        "ledger.fx.default-preferential-rate=0",
        "ledger.fx.max-preferential-rate=0.9"})
class AccountFxConversionApiTest extends IntegrationTestSupport {

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
        openAccount(accountId);
        deposit(accountId, "dep-1", "2000000");
        when(exchangeRateProvider.getExchangeRate("USD", "KRW")).thenReturn(new ExchangeRate(new BigDecimal("1300"), false));
    }

    @AfterEach
    void tearDown() {
        // 입금과 환전이 남긴 아웃박스 이벤트를 소비자가 원장에 기록하는 중에 정리하면 deadlock 이 난다(#140).
        awaitLedgerPipelineIdle();
        jdbcTemplate.execute("TRUNCATE TABLE account_status_history, outbox_events, transaction_entries, transactions, "
                + "monthly_account_ledgers, idempotency_records, fx_conversions CASCADE");
        deleteTestAccounts();
    }

    // ── 도우미 ──────────────────────────────────────────────────────────────────────────────

    private ResultActions admin(MockHttpServletRequestBuilder request, String body) throws Exception {
        return mockMvc.perform(request
                .header(HeaderPrincipalResolver.SUBJECT_HEADER, "admin-1")
                .header(HeaderPrincipalResolver.ROLES_HEADER, "ADMIN")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private void openAccount(UUID id) throws Exception {
        admin(post("/api/v1/admin/accounts"),
                "{\"accountId\":\"" + id + "\",\"ownerName\":\"FX_USER\",\"baseCurrency\":\"KRW\"}")
                .andExpect(status().isCreated());
    }

    private void deposit(UUID id, String key, String amount) throws Exception {
        admin(post("/api/v1/admin/accounts/{id}/deposits", id),
                "{\"idempotencyKey\":\"" + key + "\",\"currency\":\"KRW\",\"amount\":" + amount + "}")
                .andExpect(status().isOk());
    }

    private ResultActions asOwner(UUID principalAccount, String path, UUID targetAccount, String body) throws Exception {
        return mockMvc.perform(post(path, targetAccount)
                .header(HeaderPrincipalResolver.SUBJECT_HEADER, "user-" + principalAccount)
                .header(HeaderPrincipalResolver.ACCOUNT_HEADER, principalAccount.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    /** API 로 견적을 받는다. */
    private UUID quote(UUID id, String from, String to, String amount) throws Exception {
        String response = asOwner(id, "/api/v1/accounts/{id}/fx-quotes", id,
                "{\"fromCurrency\":\"" + from + "\",\"toCurrency\":\"" + to + "\",\"amount\":" + amount + "}")
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(jsonMapper.readTree(response).get("quoteId").asString());
    }

    private ResultActions convert(UUID id, UUID quoteId, String key) throws Exception {
        return asOwner(id, "/api/v1/accounts/{id}/fx-conversions", id,
                "{\"quoteId\":\"" + quoteId + "\",\"idempotencyKey\":\"" + key + "\"}");
    }

    private BigDecimal balance(UUID id, String currency) {
        List<BigDecimal> rows = jdbcTemplate.queryForList("SELECT balance FROM monthly_account_ledgers "
                + "WHERE account_id = ? AND asset_code = ? ORDER BY ledger_month DESC LIMIT 1",
                BigDecimal.class, id, currency);
        return rows.isEmpty() ? BigDecimal.ZERO : rows.get(0);
    }

    private int conversionCount() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM fx_conversions", Integer.class);
    }

    // ── 체결 ────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("원화로 달러를 사면 견적대로 잔고가 옮겨지고 체결 기록이 남는다")
    void buyingUsdMovesTheBalancesAtTheQuotedRate() throws Exception {
        // 시세 1,300 · 스프레드 1.75% · 우대 없음 → 환율 1,322.75. 1,322,750 원 → 1,000 USD.
        UUID quoteId = quote(accountId, "KRW", "USD", "1322750");

        convert(accountId, quoteId, "conv-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conversionId").isNotEmpty())
                .andExpect(jsonPath("$.quoteId").value(quoteId.toString()))
                .andExpect(jsonPath("$.fromCurrency").value("KRW"))
                .andExpect(jsonPath("$.fromAmount").value(1322750))
                .andExpect(jsonPath("$.toCurrency").value("USD"))
                .andExpect(jsonPath("$.toAmount").value(1000))
                .andExpect(jsonPath("$.customerRate").value(1322.75))
                .andExpect(jsonPath("$.convertedAt").isNotEmpty());

        assertThat(balance(accountId, "KRW")).isEqualByComparingTo("677250");
        assertThat(balance(accountId, "USD")).isEqualByComparingTo("1000");
        Map<String, Object> row = jdbcTemplate.queryForMap("SELECT * FROM fx_conversions");
        assertThat(row.get("quote_id")).isEqualTo(quoteId);
        assertThat((BigDecimal) row.get("market_value")).isEqualByComparingTo("1300000");
        assertThat((BigDecimal) row.get("spread_revenue")).isEqualByComparingTo("22750");
    }

    @Test
    @DisplayName("체결가는 견적에 고정된다: 견적 뒤에 시세가 움직여도 견적의 환율로 체결된다")
    void theQuotedRateIsHonouredAfterTheMarketMoves() throws Exception {
        UUID quoteId = quote(accountId, "KRW", "USD", "1322750");
        when(exchangeRateProvider.getExchangeRate("USD", "KRW")).thenReturn(new ExchangeRate(new BigDecimal("1500"), false));

        convert(accountId, quoteId, "conv-1").andExpect(status().isOk()).andExpect(jsonPath("$.toAmount").value(1000));

        assertThat(balance(accountId, "USD")).isEqualByComparingTo("1000");
    }

    @Test
    @DisplayName("달러를 팔면 원화가 늘고 달러가 줄어든다")
    void sellingUsdMovesTheBalances() throws Exception {
        convert(accountId, quote(accountId, "KRW", "USD", "1322750"), "buy-1").andExpect(status().isOk());

        UUID sellQuote = quote(accountId, "USD", "KRW", "1000");
        convert(accountId, sellQuote, "sell-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fromCurrency").value("USD"))
                .andExpect(jsonPath("$.toAmount").value(1277250));

        assertThat(balance(accountId, "USD")).isEqualByComparingTo("0");
        // 2,000,000 − 1,322,750 + 1,277,250
        assertThat(balance(accountId, "KRW")).isEqualByComparingTo("1954500");
        assertThat(conversionCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("체결한 견적은 저장소에서 지워진다")
    void theQuoteIsDiscardedAfterTheConversion() throws Exception {
        UUID quoteId = quote(accountId, "KRW", "USD", "1322750");
        assertThat(quoteStore.find(quoteId)).isPresent();

        convert(accountId, quoteId, "conv-1").andExpect(status().isOk());

        assertThat(quoteStore.find(quoteId)).isEmpty();
    }

    /** 포트폴리오 API 가 보여 주는 통화별 수량. 소수 자릿수가 달라도 같은 값으로 비교하려고 BigDecimal 로 읽는다. */
    private BigDecimal portfolioQuantity(String assetCode) throws Exception {
        String body = mockMvc.perform(get("/api/v1/portfolios/{id}", accountId)
                        .header(HeaderPrincipalResolver.SUBJECT_HEADER, "user-1")
                        .header(HeaderPrincipalResolver.ACCOUNT_HEADER, accountId.toString()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        for (tools.jackson.databind.JsonNode asset : jsonMapper.readTree(body).get("assets")) {
            if (assetCode.equals(asset.get("assetCode").asString())) {
                return new BigDecimal(asset.get("quantity").asString());
            }
        }
        return BigDecimal.ZERO;
    }

    @Test
    @DisplayName("환전 직후 포트폴리오 조회에는 바뀐 잔고가 보인다 (캐시가 먼저 채워져 있었어도)")
    void thePortfolioReflectsAConversionImmediately() throws Exception {
        // 환전 전에 한 번 조회해 포트폴리오 캐시를 채운다. 이 캐시가 환전 뒤에도 남으면 낡은 잔고가 보인다.
        assertThat(portfolioQuantity("KRW")).isEqualByComparingTo("2000000");
        assertThat(portfolioQuantity("USD")).isEqualByComparingTo("0");

        convert(accountId, quote(accountId, "KRW", "USD", "1322750"), "conv-1").andExpect(status().isOk());

        assertThat(portfolioQuantity("KRW")).isEqualByComparingTo("677250");
        assertThat(portfolioQuantity("USD")).isEqualByComparingTo("1000");
    }

    // ── 멱등성과 단일 사용 ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("같은 멱등성 키로 다시 보내면 처음 체결을 돌려받고 잔고는 한 번만 바뀐다 (견적이 지워진 뒤에도)")
    void replayingTheSameKeyReturnsTheFirstConversion() throws Exception {
        UUID quoteId = quote(accountId, "KRW", "USD", "1322750");
        String first = convert(accountId, quoteId, "conv-1").andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String second = convert(accountId, quoteId, "conv-1").andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(jsonMapper.readTree(second).get("conversionId")).isEqualTo(jsonMapper.readTree(first).get("conversionId"));
        assertThat(balance(accountId, "USD")).isEqualByComparingTo("1000");
        assertThat(balance(accountId, "KRW")).isEqualByComparingTo("677250");
        assertThat(conversionCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("멱등성 키를 바꿔 같은 견적을 다시 쓰면 거부한다 (견적 정리가 실패한 경우를 흉내)")
    void aQuoteCannotBeConvertedTwiceWithDifferentKeys() throws Exception {
        UUID quoteId = quote(accountId, "KRW", "USD", "1322750");
        FxQuote saved = quoteStore.find(quoteId).orElseThrow();
        convert(accountId, quoteId, "conv-1").andExpect(status().isOk());
        // 체결 뒤 견적을 지우는 정리가 실패해 견적이 남아 있는 상황.
        quoteStore.save(saved, Duration.ofSeconds(30));

        convert(accountId, quoteId, "conv-2")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("FX_QUOTE_ALREADY_USED"));

        assertThat(balance(accountId, "USD")).as("두 번째 체결은 잔고를 바꾸지 않는다").isEqualByComparingTo("1000");
        assertThat(conversionCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("같은 견적을 서로 다른 키로 동시에 체결하면 정확히 하나만 성공한다")
    void concurrentConversionsOfOneQuoteSucceedOnlyOnce() throws Exception {
        UUID quoteId = quote(accountId, "KRW", "USD", "1322750");
        int attempts = 6;
        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Integer>> results = new java.util.ArrayList<>();
            for (int i = 0; i < attempts; i++) {
                String key = "race-" + i;
                Callable<Integer> task = () -> {
                    start.await();
                    return convert(accountId, quoteId, key).andReturn().getResponse().getStatus();
                };
                results.add(pool.submit(task));
            }
            start.countDown();

            int ok = 0;
            for (Future<Integer> result : results) {
                int code = result.get();
                if (code == 200) {
                    ok++;
                } else {
                    // 먼저 끝난 요청이 견적을 지웠으면 422, 아직이면 409 다. 둘 다 "이 견적으로는 안 된다"는 같은 뜻이다.
                    assertThat(code).as("실패한 요청의 상태").isIn(409, 422);
                }
            }
            assertThat(ok).as("성공한 체결 수").isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }

        assertThat(balance(accountId, "USD")).isEqualByComparingTo("1000");
        assertThat(balance(accountId, "KRW")).isEqualByComparingTo("677250");
        assertThat(conversionCount()).isEqualTo(1);
    }

    // ── 견적 문제 ───────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("없는 견적은 422 FX_QUOTE_EXPIRED 다")
    void anUnknownQuoteIsRejected() throws Exception {
        convert(accountId, UUID.randomUUID(), "conv-1")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("FX_QUOTE_EXPIRED"));

        assertThat(conversionCount()).isZero();
    }

    @Test
    @DisplayName("만료 시각이 지난 견적은 저장소에 남아 있어도 422 FX_QUOTE_EXPIRED 다")
    void anExpiredQuoteIsRejected() throws Exception {
        FxQuote expired = new FxQuote(UUID.randomUUID(), accountId, com.github.raonjena99.multi_currency_ledger_service
                .account.domain.FxDirection.BUY_FOREIGN, "KRW", new BigDecimal("1322750"), "USD",
                new BigDecimal("1000"), new BigDecimal("1300"), new BigDecimal("1322.75"), new BigDecimal("0.0175"),
                BigDecimal.ZERO, OffsetDateTime.now().minusSeconds(40), OffsetDateTime.now().minusSeconds(10));
        quoteStore.save(expired, Duration.ofSeconds(30));

        convert(accountId, expired.quoteId(), "conv-1")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("FX_QUOTE_EXPIRED"));

        assertThat(balance(accountId, "KRW")).isEqualByComparingTo("2000000");
    }

    @Test
    @DisplayName("다른 계좌의 견적은 없는 견적과 똑같이 422 로 거부한다")
    void anotherAccountsQuoteIsRejectedLikeAMissingOne() throws Exception {
        UUID other = UUID.randomUUID();
        openAccount(other);
        deposit(other, "dep-other", "2000000");
        UUID othersQuote = quote(other, "KRW", "USD", "1322750");

        convert(accountId, othersQuote, "conv-1")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("FX_QUOTE_EXPIRED"));

        assertThat(quoteStore.find(othersQuote)).as("남의 견적은 건드리지 않는다").isPresent();
        assertThat(balance(other, "KRW")).isEqualByComparingTo("2000000");
    }

    // ── 접근·입력·계좌 상태 ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("다른 계좌의 소유자는 환전할 수 없다 (403)")
    void onlyTheOwnerCanConvert() throws Exception {
        UUID quoteId = quote(accountId, "KRW", "USD", "1322750");
        UUID stranger = UUID.randomUUID();

        asOwner(stranger, "/api/v1/accounts/{id}/fx-conversions", accountId,
                "{\"quoteId\":\"" + quoteId + "\",\"idempotencyKey\":\"conv-1\"}")
                .andExpect(status().isForbidden());

        assertThat(conversionCount()).isZero();
    }

    @Test
    @DisplayName("잔고가 모자라면 409 INSUFFICIENT_BALANCE 이고, 견적은 그대로 남는다")
    void insufficientBalanceLeavesTheQuoteUsable() throws Exception {
        UUID quoteId = quote(accountId, "KRW", "USD", "3000000");

        convert(accountId, quoteId, "conv-1")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_BALANCE"));

        assertThat(balance(accountId, "KRW")).isEqualByComparingTo("2000000");
        assertThat(conversionCount()).isZero();
        assertThat(quoteStore.find(quoteId)).isPresent();
    }

    @Test
    @DisplayName("멱등성 키가 없거나 견적 ID 가 UUID 가 아니면 400 이다")
    void invalidRequestsAreRejected() throws Exception {
        UUID quoteId = quote(accountId, "KRW", "USD", "1322750");

        asOwner(accountId, "/api/v1/accounts/{id}/fx-conversions", accountId, "{\"quoteId\":\"" + quoteId + "\"}")
                .andExpect(status().isBadRequest());
        asOwner(accountId, "/api/v1/accounts/{id}/fx-conversions", accountId,
                "{\"quoteId\":\"" + quoteId + "\",\"idempotencyKey\":\"  \"}")
                .andExpect(status().isBadRequest());
        asOwner(accountId, "/api/v1/accounts/{id}/fx-conversions", accountId, "{\"idempotencyKey\":\"conv-1\"}")
                .andExpect(status().isBadRequest());
        asOwner(accountId, "/api/v1/accounts/{id}/fx-conversions", accountId,
                "{\"quoteId\":\"not-a-uuid\",\"idempotencyKey\":\"conv-1\"}")
                .andExpect(status().isBadRequest());

        assertThat(conversionCount()).isZero();
    }

    @Test
    @DisplayName("정지된 계좌는 환전할 수 없다 (422)")
    void aSuspendedAccountCannotConvert() throws Exception {
        UUID quoteId = quote(accountId, "KRW", "USD", "1322750");
        admin(post("/api/v1/admin/accounts/{id}/suspend", accountId), "{\"reason\":\"test\"}")
                .andExpect(status().isOk());

        convert(accountId, quoteId, "conv-1")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("INVALID_ACCOUNT_STATE"));

        assertThat(conversionCount()).isZero();
    }
}
