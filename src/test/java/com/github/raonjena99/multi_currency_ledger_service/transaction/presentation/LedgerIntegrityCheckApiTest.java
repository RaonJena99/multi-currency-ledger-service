package com.github.raonjena99.multi_currency_ledger_service.transaction.presentation;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import com.github.raonjena99.multi_currency_ledger_service.common.security.HeaderPrincipalResolver;

import tools.jackson.databind.json.JsonMapper;

/**
 * 원장 정합성 점검 관리자 API 를 보안 필터까지 포함한 실제 컨텍스트로 검증합니다.
 */
@DisplayName("원장 정합성 점검 관리자 API")
class LedgerIntegrityCheckApiTest extends IntegrationTestSupport {

    private static final String BASE = "/api/v1/admin/ledger/integrity-checks";

    @Autowired private WebApplicationContext wac;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private JsonMapper jsonMapper;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("TRUNCATE TABLE ledger_integrity_mismatches, ledger_integrity_runs CASCADE");
        mockMvc = MockMvcBuilders.webAppContextSetup(wac)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.execute("TRUNCATE TABLE ledger_integrity_mismatches, ledger_integrity_runs CASCADE");
    }

    private MockHttpServletRequestBuilder asAdmin(MockHttpServletRequestBuilder request) {
        return request.header(HeaderPrincipalResolver.SUBJECT_HEADER, "admin-1")
                .header(HeaderPrincipalResolver.ROLES_HEADER, "ADMIN");
    }

    @Test
    @DisplayName("관리자는 점검을 바로 실행하고, 같은 결과를 최근 결과로 조회할 수 있다")
    void runNowThenReadLatest() throws Exception {
        String body = mockMvc.perform(asAdmin(post(BASE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trialBalanceBalanced").value(true))
                .andExpect(jsonPath("$.mismatches").isArray())
                .andReturn().getResponse().getContentAsString();
        String runId = jsonMapper.readTree(body).get("runId").asString();

        mockMvc.perform(asAdmin(get(BASE + "/latest")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runId").value(runId));
    }

    @Test
    @DisplayName("아직 점검한 적이 없으면 최근 결과는 204 다")
    void latestWithoutRunIsNoContent() throws Exception {
        mockMvc.perform(asAdmin(get(BASE + "/latest")))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("일반 사용자는 점검을 실행할 수 없다")
    void runRequiresAdmin() throws Exception {
        mockMvc.perform(post(BASE).header(HeaderPrincipalResolver.SUBJECT_HEADER, "user-1"))
                .andExpect(status().isForbidden());
    }
}
