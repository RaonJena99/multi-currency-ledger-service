package com.github.raonjena99.multi_currency_ledger_service.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
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
import com.github.raonjena99.multi_currency_ledger_service.common.port.ExchangeRateProvider;
import com.github.raonjena99.multi_currency_ledger_service.common.port.ExchangeRateProvider.ExchangeRate;
import com.github.raonjena99.multi_currency_ledger_service.common.security.HeaderPrincipalResolver;
import com.github.raonjena99.multi_currency_ledger_service.transaction.application.LedgerQueryService;
import com.github.raonjena99.multi_currency_ledger_service.transaction.application.LedgerQueryService.CurrencyTrialBalance;
import com.github.raonjena99.multi_currency_ledger_service.transaction.infrastructure.query.LedgerIntegrityDao;

import tools.jackson.databind.json.JsonMapper;

/**
 * 환전을 API 로 수행하고, 체결이 아웃박스와 Kafka 를 거쳐 복식부기 원장에 기록되는지 확인합니다.
 *
 * <p>완료 조건의 핵심은 왕복 환전(원화 → USD → 원화) 뒤 고객의 손실이 회사의 스프레드 수익과 환율 변동분으로 정확히
 * 설명되고, 시산표와 정합성 점검이 맞는 것입니다.
 */
@DisplayName("E2E: 환전 체결 → 원장")
// 기본 환전 정책(application.yaml)은 운영 정책에 맞춰 바뀌는 값이다. 이 테스트의 숫자가 정책 변경에 흔들리지 않도록 고정한다.
@TestPropertySource(properties = {
        "ledger.fx.currencies.USD.spread=0.0175",
        "ledger.fx.currencies.USD.min-amount=1",
        "ledger.fx.currencies.USD.max-amount=10000",
        "ledger.fx.default-preferential-rate=0",
        "ledger.fx.max-preferential-rate=0.9"})
class FxConversionE2ETest extends IntegrationTestSupport {

    private static final UUID SYSTEM_FX = new UUID(0, 0);
    private static final UUID SYSTEM_FEE = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Autowired private WebApplicationContext wac;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private JsonMapper jsonMapper;
    @Autowired private LedgerQueryService queryService;
    @Autowired private LedgerIntegrityDao integrityDao;

    @MockitoBean private ExchangeRateProvider exchangeRateProvider;

    private MockMvc mockMvc;
    private final UUID accountId = UUID.randomUUID();

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity())
                .build();
        admin(post("/api/v1/admin/accounts"),
                "{\"accountId\":\"" + accountId + "\",\"ownerName\":\"FX_E2E\",\"baseCurrency\":\"KRW\"}")
                .andExpect(status().isCreated());
        admin(post("/api/v1/admin/accounts/{id}/deposits", accountId),
                "{\"idempotencyKey\":\"dep-1\",\"currency\":\"KRW\",\"amount\":2000000}").andExpect(status().isOk());
        marketRate("1300");
    }

    @AfterEach
    void tearDown() {
        awaitLedgerPipelineIdle();
        jdbcTemplate.execute("TRUNCATE TABLE account_status_history, outbox_events, transaction_entries, transactions, "
                + "monthly_account_ledgers, idempotency_records, fx_conversions CASCADE");
        deleteTestAccounts();
    }

    private void marketRate(String rate) {
        when(exchangeRateProvider.getExchangeRate("USD", "KRW")).thenReturn(new ExchangeRate(new BigDecimal(rate), false));
    }

    private ResultActions admin(MockHttpServletRequestBuilder request, String body) throws Exception {
        return mockMvc.perform(request
                .header(HeaderPrincipalResolver.SUBJECT_HEADER, "admin-1")
                .header(HeaderPrincipalResolver.ROLES_HEADER, "ADMIN")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private ResultActions owner(String path, String body) throws Exception {
        return mockMvc.perform(post(path, accountId)
                .header(HeaderPrincipalResolver.SUBJECT_HEADER, "user-1")
                .header(HeaderPrincipalResolver.ACCOUNT_HEADER, accountId.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    /** 견적을 받아 바로 체결하고 체결 ID(= 원장 거래 ID)를 돌려준다. */
    private UUID convert(String from, String to, String amount, String key) throws Exception {
        String quote = owner("/api/v1/accounts/{id}/fx-quotes",
                "{\"fromCurrency\":\"" + from + "\",\"toCurrency\":\"" + to + "\",\"amount\":" + amount + "}")
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String quoteId = jsonMapper.readTree(quote).get("quoteId").asString();
        String conversion = owner("/api/v1/accounts/{id}/fx-conversions",
                "{\"quoteId\":\"" + quoteId + "\",\"idempotencyKey\":\"" + key + "\"}")
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(jsonMapper.readTree(conversion).get("conversionId").asString());
    }

    private BigDecimal balance(String currency) {
        return jdbcTemplate.queryForObject("SELECT balance FROM monthly_account_ledgers WHERE account_id = ? "
                + "AND asset_code = ? ORDER BY ledger_month DESC LIMIT 1", BigDecimal.class, accountId, currency);
    }

    private List<Map<String, Object>> entries(UUID transactionId) {
        return jdbcTemplate.queryForList("SELECT account_id, entry_type, asset_code, quantity, amount "
                + "FROM transaction_entries WHERE transaction_id = ? ORDER BY id", transactionId);
    }

    private static BigDecimal amountOf(List<Map<String, Object>> entries, UUID account, String entryType, String assetCode) {
        return entries.stream()
                .filter(e -> e.get("account_id").equals(account) && e.get("entry_type").equals(entryType)
                        && e.get("asset_code").equals(assetCode))
                .map(e -> (BigDecimal) e.get("amount")).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal systemTotal(UUID account, String assetCode, String entryType) {
        BigDecimal total = jdbcTemplate.queryForObject("SELECT COALESCE(SUM(te.amount), 0) FROM transaction_entries te "
                + "JOIN transactions t ON t.id = te.transaction_id WHERE t.transaction_type = 'FX_CONVERSION' "
                + "AND te.account_id = ? AND te.asset_code = ? AND te.entry_type = ?", BigDecimal.class,
                account, assetCode, entryType);
        return total;
    }

    // ── 왕복 ────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("같은 시세에서 왕복하면 고객 손실은 회사 스프레드 수익의 합과 정확히 같다")
    void roundTripAtTheSameMarketLosesExactlyTheSpread() throws Exception {
        UUID buy = convert("KRW", "USD", "1322750", "buy-1");    // 1,322,750 원 → 1,000 USD
        UUID sell = convert("USD", "KRW", "1000", "sell-1");     // 1,000 USD → 1,277,250 원
        awaitLedgerPipelineIdle();

        // 잔고: 2,000,000 − 1,322,750 + 1,277,250
        assertThat(balance("USD")).isEqualByComparingTo("0");
        assertThat(balance("KRW")).isEqualByComparingTo("1954500");
        BigDecimal customerLoss = new BigDecimal("2000000").subtract(balance("KRW"));
        assertThat(customerLoss).isEqualByComparingTo("45500");

        // 원장: 두 환전 모두 기록되었고, 각각 차변 = 대변 이다.
        for (UUID id : List.of(buy, sell)) {
            assertThat(jdbcTemplate.queryForObject("SELECT transaction_type FROM transactions WHERE id = ?",
                    String.class, id)).isEqualTo("FX_CONVERSION");
            List<Map<String, Object>> entries = entries(id);
            BigDecimal debit = entries.stream().filter(e -> "DEBIT".equals(e.get("entry_type")))
                    .map(e -> (BigDecimal) e.get("amount")).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal credit = entries.stream().filter(e -> "CREDIT".equals(e.get("entry_type")))
                    .map(e -> (BigDecimal) e.get("amount")).reduce(BigDecimal.ZERO, BigDecimal::add);
            assertThat(debit).as("거래 %s 의 차변과 대변", id).isEqualByComparingTo(credit);
        }

        // 스프레드 수익: 회사(시스템 수수료 계정)가 번 금액의 합 = 고객 손실.
        BigDecimal revenue = amountOf(entries(buy), SYSTEM_FEE, "CREDIT", "SYSTEM_FX_SPREAD")
                .add(amountOf(entries(sell), SYSTEM_FEE, "CREDIT", "SYSTEM_FX_SPREAD"));
        assertThat(revenue).isEqualByComparingTo("45500");

        // 고객의 실현 환차손은 외화를 판 환전에 남는다. 외화를 살 때는 원화(평균 단가 1)가 나가므로 손익이 없다.
        assertThat(amountOf(entries(sell), accountId, "DEBIT", "REALIZED_PNL_FX")).isEqualByComparingTo("45500");
        assertThat(entries(buy)).noneMatch(e -> e.get("asset_code").toString().startsWith("REALIZED_PNL_"));

        // 회사의 외화 포지션은 왕복 뒤 0 이다. 산 만큼 내주고 판 만큼 받았다.
        BigDecimal usdOut = systemTotal(SYSTEM_FX, "USD", "CREDIT");
        BigDecimal usdIn = systemTotal(SYSTEM_FX, "USD", "DEBIT");
        assertThat(usdIn).isEqualByComparingTo(usdOut);
    }

    @Test
    @DisplayName("시세가 오른 뒤 팔면 고객 손익은 환율 변동분에서 스프레드를 뺀 값이다")
    void roundTripAfterTheMarketRisesGainsTheMoveMinusTheSpread() throws Exception {
        UUID buy = convert("KRW", "USD", "1322750", "buy-1");    // 시세 1,300: 1,000 USD, 스프레드 22,750
        marketRate("1400");
        UUID sell = convert("USD", "KRW", "1000", "sell-1");     // 시세 1,400: 1,000 × 1,400 × 0.9825 = 1,375,500
        awaitLedgerPipelineIdle();

        assertThat(balance("KRW")).isEqualByComparingTo("2052750");   // 2,000,000 − 1,322,750 + 1,375,500
        BigDecimal customerGain = balance("KRW").subtract(new BigDecimal("2000000"));

        // 환율 변동분 (1,400 − 1,300) × 1,000 = 100,000 에서 스프레드 22,750 + 24,500 을 뺀 값.
        BigDecimal sellRevenue = amountOf(entries(sell), SYSTEM_FEE, "CREDIT", "SYSTEM_FX_SPREAD");
        BigDecimal buyRevenue = amountOf(entries(buy), SYSTEM_FEE, "CREDIT", "SYSTEM_FX_SPREAD");
        assertThat(buyRevenue).isEqualByComparingTo("22750");
        assertThat(sellRevenue).isEqualByComparingTo("24500");
        assertThat(customerGain).isEqualByComparingTo(new BigDecimal("100000").subtract(buyRevenue).subtract(sellRevenue));

        // 이익은 고객 계정의 대변 분개다.
        assertThat(amountOf(entries(sell), accountId, "CREDIT", "REALIZED_PNL_FX")).isEqualByComparingTo("52750");
    }

    // ── 회사 포지션 ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("회사의 합산 손익은 고객 손익의 정확한 반대이고, 달러 포지션은 왕복 뒤 0 이다")
    void companyPositionMirrorsTheCustomerResult() throws Exception {
        convert("KRW", "USD", "1322750", "buy-1");    // 시세 1,300
        marketRate("1400");
        convert("USD", "KRW", "1000", "sell-1");      // 시세 1,400
        awaitLedgerPipelineIdle();
        BigDecimal customerGain = balance("KRW").subtract(new BigDecimal("2000000"));   // +52,750

        String body = admin(get("/api/v1/admin/ledger/fx-positions"), "").andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        tools.jackson.databind.JsonNode book = jsonMapper.readTree(body).get("books").get(0);

        assertThat(book.get("baseCurrency").asString()).isEqualTo("KRW");
        assertThat(new BigDecimal(book.get("spreadRevenue").asString())).isEqualByComparingTo("47250");
        BigDecimal usdQuantity = null;
        BigDecimal marketValueTotal = BigDecimal.ZERO;
        for (tools.jackson.databind.JsonNode position : book.get("positions")) {
            marketValueTotal = marketValueTotal.add(new BigDecimal(position.get("marketValue").asString()));
            if ("USD".equals(position.get("currency").asString())) {
                usdQuantity = new BigDecimal(position.get("netQuantity").asString());
            }
        }
        assertThat(usdQuantity).as("산 만큼 되팔았으니 달러 포지션은 0").isEqualByComparingTo("0");
        BigDecimal total = new BigDecimal(book.get("totalResult").asString());
        assertThat(total).as("회사 합산 손익 = 스프레드 47,250 − 환손실 100,000").isEqualByComparingTo("-52750");
        assertThat(total).as("회사 손익은 고객 손익의 반대").isEqualByComparingTo(customerGain.negate());
        assertThat(total).as("합산 손익 = 포지션 시장가치의 합").isEqualByComparingTo(marketValueTotal);
    }

    // ── 점검 ────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("환전 뒤에도 시산표는 차변 = 대변 이고 정합성 점검에서 이 계좌의 차이는 없다")
    void trialBalanceAndIntegrityHoldAfterConversions() throws Exception {
        convert("KRW", "USD", "1322750", "buy-1");
        convert("USD", "KRW", "400", "sell-1");
        awaitLedgerPipelineIdle();

        YearMonth month = YearMonth.from(OffsetDateTime.now(ZoneOffset.UTC));
        CurrencyTrialBalance krw = queryService.trialBalance(month).currencies().stream()
                .filter(c -> "KRW".equals(c.currency())).findFirst().orElseThrow();
        assertThat(krw.balanced()).isTrue();
        assertThat(krw.debitTotal()).isEqualByComparingTo(krw.creditTotal());

        // 잔고(월차 원장)와 분개 누계가 (계좌, 통화) 별로 일치한다 — 환전의 양쪽 잔고 이동이 분개와 맞다는 뜻이다.
        assertThat(integrityDao.findDifferences())
                .filteredOn(d -> d.accountId().equals(accountId))
                .as("이 계좌의 잔고와 분개 차이").isEmpty();
        assertThat(balance("USD")).isEqualByComparingTo("600");
    }
}
