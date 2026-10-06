package com.github.raonjena99.multi_currency_ledger_service.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.github.raonjena99.multi_currency_ledger_service.IntegrationTestSupport;
import com.github.raonjena99.multi_currency_ledger_service.common.outbox.OutboxRelayWorker;
import com.github.raonjena99.multi_currency_ledger_service.common.port.ExchangeRateProvider;
import com.github.raonjena99.multi_currency_ledger_service.common.security.HeaderPrincipalResolver;
import com.github.raonjena99.multi_currency_ledger_service.reconciliation.infrastructure.query.InternalTransactionCandidate;
import com.github.raonjena99.multi_currency_ledger_service.reconciliation.infrastructure.query.InternalTransactionQueryDao;

import tools.jackson.databind.json.JsonMapper;

/**
 * 계좌 개설부터 출금까지를 API 만으로 수행하고, 각 거래가 Kafka 를 거쳐 복식부기 원장에 기록되는지 확인합니다.
 *
 * 개설(관리자) → 입금(관리자) → 매수(고객) → 매도(고객) → 출금(관리자) → 포트폴리오 조회(고객)
 */
@DisplayName("E2E: 계좌 개설 → 입금 → 매수 → 매도 → 출금 → 원장")
class CashFlowE2ETest extends IntegrationTestSupport {

    private static final UUID CASH_CLEARING_ACCOUNT_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Autowired private WebApplicationContext wac;
    @Autowired private OutboxRelayWorker relayWorker;
    @Autowired private InternalTransactionQueryDao candidateQueryDao;
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
        org.mockito.Mockito.when(exchangeRateProvider.getExchangeRate("BTC", "KRW"))
                .thenReturn(new ExchangeRateProvider.ExchangeRate(new BigDecimal("100"), false));
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.execute("TRUNCATE TABLE outbox_events, transaction_entries, transactions, "
                + "monthly_account_ledgers, idempotency_records, ledger_dead_letters CASCADE");
        deleteTestAccounts();
    }

    private MockHttpServletRequestBuilder asAdmin(MockHttpServletRequestBuilder request) {
        return request.header(HeaderPrincipalResolver.SUBJECT_HEADER, "admin-1")
                .header(HeaderPrincipalResolver.ROLES_HEADER, "ADMIN");
    }

    private MockHttpServletRequestBuilder asOwner(MockHttpServletRequestBuilder request) {
        return request.header(HeaderPrincipalResolver.SUBJECT_HEADER, "user-1")
                .header(HeaderPrincipalResolver.ACCOUNT_HEADER, accountId.toString());
    }

    private UUID postForId(MockHttpServletRequestBuilder request, String body, String idField) throws Exception {
        String response = mockMvc.perform(request.contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is2xxSuccessful())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(jsonMapper.readTree(response).get(idField).asString());
    }

    private String cashBody(String key, String amount) {
        return "{\"idempotencyKey\":\"" + key + "\",\"currency\":\"KRW\",\"amount\":" + amount + "}";
    }

    private String tradeBody(String key, String quantity) {
        return "{\"idempotencyKey\":\"" + key + "\",\"targetAssetCode\":\"BTC\",\"targetAssetType\":\"CRYPTO\","
                + "\"paymentCurrency\":\"KRW\",\"quantity\":" + quantity + ",\"unitPrice\":100}";
    }

    private BigDecimal quantityOf(String portfolioJson, String assetCode) {
        for (var asset : jsonMapper.readTree(portfolioJson).get("assets")) {
            if (assetCode.equals(asset.get("assetCode").asString())) {
                return asset.get("quantity").decimalValue();
            }
        }
        throw new AssertionError("포트폴리오에 " + assetCode + " 가 없습니다: " + portfolioJson);
    }

    private BigDecimal sum(UUID accountId, String entryType) {
        return jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(amount), 0) FROM transaction_entries WHERE account_id = ? AND entry_type = ?",
                BigDecimal.class, accountId, entryType);
    }

    @Test
    @DisplayName("API 만으로 개설부터 출금까지 수행하고, 모든 거래가 원장에 대차 일치로 기록된다")
    void fullCashFlowReachesLedger() throws Exception {
        // 개설
        mockMvc.perform(asAdmin(post("/api/v1/admin/accounts"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountId\":\"" + accountId + "\",\"ownerName\":\"E2E_USER\",\"baseCurrency\":\"KRW\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accountId").value(accountId.toString()));

        // 입금 1,000,000 → 매수 2 BTC × 100 → 매도 1 BTC × 100 → 출금 500
        UUID deposit = postForId(asAdmin(post("/api/v1/admin/accounts/{id}/deposits", accountId)),
                cashBody("dep-1", "1000000"), "transactionId");
        UUID buy = postForId(asOwner(post("/api/v1/accounts/{id}/trades/buy", accountId)),
                tradeBody("buy-1", "2"), "tradeId");
        UUID sell = postForId(asOwner(post("/api/v1/accounts/{id}/trades/sell", accountId)),
                tradeBody("sell-1", "1"), "tradeId");
        UUID withdrawal = postForId(asAdmin(post("/api/v1/admin/accounts/{id}/withdrawals", accountId)),
                cashBody("wd-1", "500"), "transactionId");

        // 포트폴리오: 1,000,000 − 200 + 100 − 500 = 999,400 KRW, 1 BTC
        String portfolio = mockMvc.perform(asOwner(get("/api/v1/portfolios/{id}", accountId)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(quantityOf(portfolio, "KRW")).isEqualByComparingTo("999400");
        assertThat(quantityOf(portfolio, "BTC")).isEqualByComparingTo("1");

        // 원장: 네 거래 모두 Kafka 를 거쳐 기록된다
        relayWorker.relayOutboxEvents();
        List<UUID> transactionIds = List.of(deposit, buy, sell, withdrawal);
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(500)).untilAsserted(() ->
                assertThat(jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM transactions WHERE id IN (?, ?, ?, ?)", Integer.class,
                        deposit, buy, sell, withdrawal)).isEqualTo(4));

        for (UUID id : transactionIds) {
            List<Map<String, Object>> entries = jdbcTemplate.queryForList(
                    "SELECT entry_type, amount, realized_pnl FROM transaction_entries WHERE transaction_id = ?", id);
            BigDecimal debit = entries.stream().filter(e -> "DEBIT".equals(e.get("entry_type")))
                    .map(e -> (BigDecimal) e.get("amount")).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal creditWithPnl = entries.stream().filter(e -> "CREDIT".equals(e.get("entry_type")))
                    .map(e -> ((BigDecimal) e.get("amount")).add(
                            e.get("realized_pnl") == null ? BigDecimal.ZERO : (BigDecimal) e.get("realized_pnl")))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            assertThat(debit).as("거래 %s 의 차변과 대변(실현손익 포함)이 일치해야 한다", id)
                    .isEqualByComparingTo(creditWithPnl);
        }

        // 입출금의 상대 계정은 외부 입출금 청산 계정이다
        assertThat(sum(CASH_CLEARING_ACCOUNT_ID, "CREDIT")).isEqualByComparingTo("1000000");
        assertThat(sum(CASH_CLEARING_ACCOUNT_ID, "DEBIT")).isEqualByComparingTo("500");

        // 입출금은 PG 정산과 맞춰 볼 대상이 아니다
        List<UUID> candidates = candidateQueryDao
                .fetchCandidatesForPeriod(OffsetDateTime.now().minusDays(1), OffsetDateTime.now().plusDays(1))
                .stream().map(InternalTransactionCandidate::transactionId).toList();
        assertThat(candidates).doesNotContain(deposit, withdrawal);
    }

    @Test
    @DisplayName("일반 사용자는 자기 계좌라도 입금할 수 없다")
    void customerCannotDeposit() throws Exception {
        mockMvc.perform(asOwner(post("/api/v1/admin/accounts/{id}/deposits", accountId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(cashBody("dep-1", "1000000")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("이미 있는 계좌 ID 로 개설하면 409 다")
    void openingExistingAccountIsConflict() throws Exception {
        String body = "{\"accountId\":\"" + accountId + "\",\"ownerName\":\"E2E_USER\",\"baseCurrency\":\"KRW\"}";
        mockMvc.perform(asAdmin(post("/api/v1/admin/accounts")).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());

        mockMvc.perform(asAdmin(post("/api/v1/admin/accounts")).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ACCOUNT_ALREADY_EXISTS"));
    }

    @Test
    @DisplayName("잔고보다 많이 출금하면 409 다")
    void overdrawingIsConflict() throws Exception {
        mockMvc.perform(asAdmin(post("/api/v1/admin/accounts")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountId\":\"" + accountId + "\",\"ownerName\":\"E2E_USER\",\"baseCurrency\":\"KRW\"}"))
                .andExpect(status().isCreated());

        mockMvc.perform(asAdmin(post("/api/v1/admin/accounts/{id}/withdrawals", accountId))
                        .contentType(MediaType.APPLICATION_JSON).content(cashBody("wd-1", "1")))
                .andExpect(status().isConflict());
    }
}
