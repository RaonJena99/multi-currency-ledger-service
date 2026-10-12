package com.github.raonjena99.multi_currency_ledger_service.account.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.github.raonjena99.multi_currency_ledger_service.IntegrationTestSupport;
import com.github.raonjena99.multi_currency_ledger_service.account.application.FxProperties;
import com.github.raonjena99.multi_currency_ledger_service.common.security.HeaderPrincipalResolver;

/**
 * 계좌별 환전 우대율 관리자 API 를 보안 필터까지 포함한 실제 컨텍스트로 검증합니다.
 *
 * <p>우대율은 회사의 스프레드 수익을 깎는 값이므로 관리자만 바꿀 수 있어야 하고, 상한(기본 0.9)을 넘거나
 * 100% 우대가 되어서는 안 됩니다.
 */
@DisplayName("환전 우대율 관리자 API")
class AccountFxAdminApiTest extends IntegrationTestSupport {

    @Autowired private WebApplicationContext wac;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private FxProperties fxProperties;

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
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.execute("TRUNCATE TABLE account_status_history, outbox_events, transaction_entries, transactions, "
                + "monthly_account_ledgers, idempotency_records CASCADE");
        deleteTestAccounts();
    }

    private ResultActions admin(MockHttpServletRequestBuilder request, String body) throws Exception {
        return mockMvc.perform(request
                .header(HeaderPrincipalResolver.SUBJECT_HEADER, "admin-1")
                .header(HeaderPrincipalResolver.ROLES_HEADER, "ADMIN")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private ResultActions setRate(UUID id, String json) throws Exception {
        return admin(put("/api/v1/admin/accounts/{id}/fx-preferential-rate", id), json);
    }

    private BigDecimal storedRate() {
        return jdbcTemplate.queryForObject("SELECT fx_preferential_rate FROM accounts WHERE id = ?",
                BigDecimal.class, accountId);
    }

    @Test
    @DisplayName("지정하지 않은 계좌는 기본 우대율이 적용된다고 알려 준다")
    void unsetAccountReportsTheDefault() throws Exception {
        mockMvc.perform(get("/api/v1/admin/accounts/{id}/fx-preferential-rate", accountId)
                        .header(HeaderPrincipalResolver.SUBJECT_HEADER, "admin-1")
                        .header(HeaderPrincipalResolver.ROLES_HEADER, "ADMIN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rate").value(fxProperties.getDefaultPreferentialRate().doubleValue()))
                .andExpect(jsonPath("$.explicit").value(false));
    }

    @Test
    @DisplayName("우대율을 지정하면 저장되고 조회된다 (상한 값까지 허용)")
    void setRateIsStoredAndReadBack() throws Exception {
        // 설정의 상한 값은 허용 범위의 끝이다. 정책 상한이 바뀌어도 이 테스트는 그 값을 따라간다.
        BigDecimal max = fxProperties.getMaxPreferentialRate();

        setRate(accountId, "{\"rate\":" + max.toPlainString() + "}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rate").value(max.doubleValue()))
                .andExpect(jsonPath("$.explicit").value(true));

        assertThat(storedRate()).isEqualByComparingTo(max);
        mockMvc.perform(get("/api/v1/admin/accounts/{id}/fx-preferential-rate", accountId)
                        .header(HeaderPrincipalResolver.SUBJECT_HEADER, "admin-1")
                        .header(HeaderPrincipalResolver.ROLES_HEADER, "ADMIN"))
                .andExpect(jsonPath("$.rate").value(max.doubleValue()))
                .andExpect(jsonPath("$.explicit").value(true));
    }

    @Test
    @DisplayName("0 은 우대 없음을 명시한 값으로 저장된다")
    void zeroIsAnExplicitValue() throws Exception {
        setRate(accountId, "{\"rate\":0}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.explicit").value(true));

        assertThat(storedRate()).isNotNull().isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("상한을 넘거나 100% 우대, 음수, 소수 4자리를 넘는 값은 400 이다")
    void invalidRatesAreRejected() throws Exception {
        String justAboveMax = fxProperties.getMaxPreferentialRate().add(new BigDecimal("0.0001")).toPlainString();
        for (String invalid : new String[] {justAboveMax, "1", "1.5", "-0.1", "0.12345"}) {
            setRate(accountId, "{\"rate\":" + invalid + "}")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        }
        assertThat(storedRate()).as("거부된 요청은 저장되지 않는다").isNull();
    }

    @Test
    @DisplayName("우대율이 없는 요청은 400 이다")
    void missingRateIsRejected() throws Exception {
        setRate(accountId, "{}").andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("없는 계좌는 404 다")
    void unknownAccountIsNotFound() throws Exception {
        setRate(UUID.randomUUID(), "{\"rate\":0.5}")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"));
    }

    @Test
    @DisplayName("계좌 소유자는 자기 계좌의 우대율도 바꿀 수 없다 (403)")
    void ownersCannotChangeTheirOwnRate() throws Exception {
        mockMvc.perform(put("/api/v1/admin/accounts/{id}/fx-preferential-rate", accountId)
                        .header(HeaderPrincipalResolver.SUBJECT_HEADER, "user-1")
                        .header(HeaderPrincipalResolver.ACCOUNT_HEADER, accountId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rate\":0.9}"))
                .andExpect(status().isForbidden());

        assertThat(storedRate()).isNull();
    }

    @Test
    @DisplayName("DB 제약도 100% 우대와 음수를 막는다")
    void databaseConstraintRejectsOutOfRangeRates() {
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE accounts SET fx_preferential_rate = 1 WHERE id = ?", accountId))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE accounts SET fx_preferential_rate = -0.0001 WHERE id = ?", accountId))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
}
