package com.github.raonjena99.multi_currency_ledger_service.transaction.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.github.raonjena99.multi_currency_ledger_service.IntegrationTestSupport;
import com.github.raonjena99.multi_currency_ledger_service.account.domain.Account;
import com.github.raonjena99.multi_currency_ledger_service.account.infrastructure.AccountRepository;
import com.github.raonjena99.multi_currency_ledger_service.common.domain.Money;
import com.github.raonjena99.multi_currency_ledger_service.common.model.AssetType;
import com.github.raonjena99.multi_currency_ledger_service.common.security.HeaderPrincipalResolver;
import com.github.raonjena99.multi_currency_ledger_service.transaction.application.LedgerService;
import com.github.raonjena99.multi_currency_ledger_service.transaction.application.command.LedgerRecordingCommand;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 거래 내역·분개 조회와 시산표 API 를 실제 원장 데이터로 검증합니다.
 *
 * <p>원장은 실제 {@link LedgerService} 로 기록합니다. 기대값은 아래 거래에서 손으로 계산한 값입니다.
 * <pre>
 * 계좌 A (KRW)  8월 31일 23:00 UTC  입금 700         ← 한국 시간으로는 9월 1일이지만 UTC 기준 8월
 *               9월 10일            입금 1,000,000
 *               9월 11일            매수 2 BTC × 100   (차변 200 / 대변 200)
 *               9월 12일            매도 1 BTC × 150, 평균 단가 100 (차변 150 / 대변 100 + 실현손익 50)
 * 계좌 B (KRW)  9월 13일            입금 5,000
 * 계좌 C (USD)  9월 14일            입금 100 USD
 * </pre>
 */
@DisplayName("원장 조회 API")
class LedgerQueryApiTest extends IntegrationTestSupport {

    @Autowired private WebApplicationContext wac;
    @Autowired private LedgerService ledgerService;
    @Autowired private AccountRepository accountRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private JsonMapper jsonMapper;

    private MockMvc mockMvc;

    private final UUID accountA = UUID.randomUUID();
    private final UUID accountB = UUID.randomUUID();
    private final UUID accountC = UUID.randomUUID();

    private final UUID augustDepositA = UUID.randomUUID();
    private final UUID depositA = UUID.randomUUID();
    private final UUID buyA = UUID.randomUUID();
    private final UUID sellA = UUID.randomUUID();
    private final UUID depositB = UUID.randomUUID();
    private final UUID depositC = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity())
                .build();

        accountRepository.save(Account.open(accountA, "USER_A", "KRW"));
        accountRepository.save(Account.open(accountB, "USER_B", "KRW"));
        accountRepository.save(Account.open(accountC, "USER_C", "USD"));

        record(cash(augustDepositA, accountA, "KRW", "KRW", "700", "2026-09-01T08:00:00+09:00"));
        record(cash(depositA, accountA, "KRW", "KRW", "1000000", "2026-09-10T00:00:00Z"));
        record(new LedgerRecordingCommand(buyA, accountA, "BTC", AssetType.CRYPTO, "KRW", "KRW", "BUY",
                Money.of("2", AssetType.CRYPTO, "BTC"), new BigDecimal("100"), new BigDecimal("100"),
                BigDecimal.ONE, BigDecimal.ZERO, false, OffsetDateTime.parse("2026-09-11T00:00:00Z")));
        record(new LedgerRecordingCommand(sellA, accountA, "BTC", AssetType.CRYPTO, "KRW", "KRW", "SELL",
                Money.of("1", AssetType.CRYPTO, "BTC"), new BigDecimal("150"), new BigDecimal("150"),
                BigDecimal.ONE, new BigDecimal("100"), false, OffsetDateTime.parse("2026-09-12T00:00:00Z")));
        record(cash(depositB, accountB, "KRW", "KRW", "5000", "2026-09-13T00:00:00Z"));
        record(cash(depositC, accountC, "USD", "USD", "100", "2026-09-14T00:00:00Z"));
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.execute("TRUNCATE TABLE transaction_entries, transactions CASCADE");
        deleteTestAccounts();
    }

    private LedgerRecordingCommand cash(UUID id, UUID accountId, String currency, String baseCurrency,
                                        String amount, String transactedAt) {
        return new LedgerRecordingCommand(id, accountId, currency, AssetType.FIAT, currency, baseCurrency, "DEPOSIT",
                Money.of(amount, AssetType.FIAT, currency), BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE,
                BigDecimal.ZERO, false, OffsetDateTime.parse(transactedAt));
    }

    private void record(LedgerRecordingCommand command) {
        ledgerService.recordDoubleEntry(command);
    }

    private MockHttpServletRequestBuilder asOwnerOf(UUID accountId, MockHttpServletRequestBuilder request) {
        return request.header(HeaderPrincipalResolver.SUBJECT_HEADER, "user-" + accountId)
                .header(HeaderPrincipalResolver.ACCOUNT_HEADER, accountId.toString());
    }

    private MockHttpServletRequestBuilder asAdmin(MockHttpServletRequestBuilder request) {
        return request.header(HeaderPrincipalResolver.SUBJECT_HEADER, "admin-1")
                .header(HeaderPrincipalResolver.ROLES_HEADER, "ADMIN");
    }

    private JsonNode getJson(MockHttpServletRequestBuilder request) throws Exception {
        String body = mockMvc.perform(request).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return jsonMapper.readTree(body);
    }

    private List<String> transactionIds(JsonNode list) {
        List<String> ids = new ArrayList<>();
        list.get("transactions").forEach(t -> ids.add(t.get("transactionId").asString()));
        return ids;
    }

    private JsonNode currency(JsonNode trialBalance, String code) {
        for (JsonNode row : trialBalance.get("currencies")) {
            if (code.equals(row.get("currency").asString())) {
                return row;
            }
        }
        throw new AssertionError("시산표에 " + code + " 가 없습니다: " + trialBalance);
    }

    // ── 거래 내역 ──────────────────────────────────────────────

    @Test
    @DisplayName("계좌의 거래 내역은 그 계좌의 거래만 최신순으로 보여준다")
    void listsOwnTransactionsNewestFirst() throws Exception {
        JsonNode list = getJson(asOwnerOf(accountA, get("/api/v1/accounts/{id}/transactions", accountA)));

        assertThat(list.get("totalCount").asLong()).isEqualTo(4);
        assertThat(transactionIds(list)).containsExactly(
                sellA.toString(), buyA.toString(), depositA.toString(), augustDepositA.toString());
        assertThat(list.get("transactions").get(0).get("transactionType").asString()).isEqualTo("SELL");
    }

    @Test
    @DisplayName("기간을 주면 시작 시각 이상, 끝 시각 미만의 거래만 보여준다")
    void filtersByPeriod() throws Exception {
        JsonNode list = getJson(asOwnerOf(accountA, get("/api/v1/accounts/{id}/transactions", accountA)
                .param("from", "2026-09-11T00:00:00Z").param("to", "2026-09-12T00:00:00Z")));

        assertThat(transactionIds(list)).containsExactly(buyA.toString());
    }

    @Test
    @DisplayName("페이지 크기만큼 나눠 보여주고 전체 건수를 함께 준다")
    void paginates() throws Exception {
        JsonNode secondPage = getJson(asOwnerOf(accountA, get("/api/v1/accounts/{id}/transactions", accountA)
                .param("page", "1").param("size", "3")));

        assertThat(secondPage.get("totalCount").asLong()).isEqualTo(4);
        assertThat(transactionIds(secondPage)).containsExactly(augustDepositA.toString());
    }

    @Test
    @DisplayName("다른 사람의 계좌 거래 내역은 403 이다")
    void otherAccountListIsForbidden() throws Exception {
        mockMvc.perform(asOwnerOf(accountA, get("/api/v1/accounts/{id}/transactions", accountB)))
                .andExpect(status().isForbidden());
    }

    // ── 거래 상세 ──────────────────────────────────────────────

    @Test
    @DisplayName("거래 상세에는 그 계좌의 분개만 나오고 시스템 계정 분개는 나오지 않는다")
    void detailShowsOnlyOwnEntries() throws Exception {
        JsonNode detail = getJson(asOwnerOf(accountA,
                get("/api/v1/accounts/{id}/transactions/{tx}", accountA, depositA)));

        assertThat(detail.get("transactionType").asString()).isEqualTo("DEPOSIT");
        // 입금 분개는 고객 차변과 청산 계정 대변 두 건이지만, 고객에게는 자기 차변만 보인다.
        assertThat(detail.get("entries")).hasSize(1);
        JsonNode entry = detail.get("entries").get(0);
        assertThat(entry.get("entryType").asString()).isEqualTo("DEBIT");
        assertThat(entry.get("assetCode").asString()).isEqualTo("KRW");
        assertThat(entry.get("amount").decimalValue()).isEqualByComparingTo("1000000");
        assertThat(entry.get("amountCurrency").asString()).isEqualTo("KRW");
    }

    @Test
    @DisplayName("매도 상세에는 실현 손익이 함께 나온다")
    void sellDetailShowsRealizedPnl() throws Exception {
        JsonNode detail = getJson(asOwnerOf(accountA, get("/api/v1/accounts/{id}/transactions/{tx}", accountA, sellA)));

        JsonNode credit = null;
        for (JsonNode e : detail.get("entries")) {
            if ("CREDIT".equals(e.get("entryType").asString())) {
                credit = e;
            }
        }
        assertThat(credit).isNotNull();
        assertThat(credit.get("assetCode").asString()).isEqualTo("BTC");
        assertThat(credit.get("amount").decimalValue()).isEqualByComparingTo("100");
        assertThat(credit.get("realizedPnl").decimalValue()).isEqualByComparingTo("50");
    }

    @Test
    @DisplayName("다른 계좌의 거래 ID 로 상세를 조회하면 404 다")
    void detailOfAnotherAccountsTransactionIsNotFound() throws Exception {
        mockMvc.perform(asOwnerOf(accountA, get("/api/v1/accounts/{id}/transactions/{tx}", accountA, depositB)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TRANSACTION_NOT_FOUND"));
    }

    @Test
    @DisplayName("없는(아직 기록되지 않은) 거래 ID 는 404 다")
    void detailOfUnknownTransactionIsNotFound() throws Exception {
        mockMvc.perform(asOwnerOf(accountA,
                        get("/api/v1/accounts/{id}/transactions/{tx}", accountA, UUID.randomUUID())))
                .andExpect(status().isNotFound());
    }

    // ── 시산표 ──────────────────────────────────────────────

    @Test
    @DisplayName("시산표는 기준 통화별로 차변 = 대변 + 실현 손익 이 성립함을 보여준다")
    void trialBalanceByCurrency() throws Exception {
        JsonNode trial = getJson(asAdmin(get("/api/v1/admin/ledger/trial-balance").param("month", "2026-09")));

        // KRW: 차변 1,000,000 + 200 + 150 + 5,000 / 대변 1,000,000 + 200 + 100 + 5,000 / 실현손익 50
        JsonNode krw = currency(trial, "KRW");
        assertThat(krw.get("debitTotal").decimalValue()).isEqualByComparingTo("1005350");
        assertThat(krw.get("creditTotal").decimalValue()).isEqualByComparingTo("1005300");
        assertThat(krw.get("realizedPnlTotal").decimalValue()).isEqualByComparingTo("50");
        assertThat(krw.get("balanced").asBoolean()).isTrue();

        JsonNode usd = currency(trial, "USD");
        assertThat(usd.get("debitTotal").decimalValue()).isEqualByComparingTo("100");
        assertThat(usd.get("creditTotal").decimalValue()).isEqualByComparingTo("100");

        assertThat(trial.get("balanced").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("월은 UTC 기준으로 나눈다")
    void trialBalanceMonthIsUtc() throws Exception {
        JsonNode august = getJson(asAdmin(get("/api/v1/admin/ledger/trial-balance").param("month", "2026-08")));

        assertThat(currency(august, "KRW").get("debitTotal").decimalValue()).isEqualByComparingTo("700");
        assertThat(august.get("currencies")).hasSize(1);
    }

    @Test
    @DisplayName("차대가 맞지 않는 분개가 있으면 해당 통화와 전체를 불일치로 표시한다")
    void trialBalanceDetectsImbalance() throws Exception {
        UUID broken = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO transactions (id, transaction_type, transacted_at, description) "
                + "VALUES (?, 'BUY', '2026-07-15 00:00:00+00', 'broken')", broken);
        jdbcTemplate.update("INSERT INTO transaction_entries (transaction_id, account_id, entry_type, asset_code, "
                + "quantity_asset_type, quantity, quantity_currency, unit_price, amount, amount_asset_type, "
                + "amount_currency, realized_pnl, realized_pnl_asset_type, realized_pnl_currency) "
                + "VALUES (?, ?, 'DEBIT', 'KRW', 'FIAT', 10, 'KRW', 1, 10, 'FIAT', 'KRW', 0, 'FIAT', 'KRW')",
                broken, accountA);

        JsonNode july = getJson(asAdmin(get("/api/v1/admin/ledger/trial-balance").param("month", "2026-07")));

        assertThat(currency(july, "KRW").get("balanced").asBoolean()).isFalse();
        assertThat(july.get("balanced").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("시산표는 관리자만 볼 수 있다")
    void trialBalanceRequiresAdmin() throws Exception {
        mockMvc.perform(asOwnerOf(accountA, get("/api/v1/admin/ledger/trial-balance").param("month", "2026-09")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("월 형식이 틀리면 400 이다")
    void trialBalanceRejectsInvalidMonth() throws Exception {
        mockMvc.perform(asAdmin(get("/api/v1/admin/ledger/trial-balance").param("month", "2026-13")))
                .andExpect(status().isBadRequest());
    }
}
