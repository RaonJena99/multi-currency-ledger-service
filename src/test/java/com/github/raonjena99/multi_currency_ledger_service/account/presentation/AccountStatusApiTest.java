package com.github.raonjena99.multi_currency_ledger_service.account.presentation;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import com.github.raonjena99.multi_currency_ledger_service.common.security.HeaderPrincipalResolver;

/**
 * 계좌 정지·해제·해지와 상태 변경 이력, 계좌 조회 API 를 보안 필터까지 포함한 실제 컨텍스트로 검증합니다.
 */
@DisplayName("계좌 상태 관리 API")
class AccountStatusApiTest extends IntegrationTestSupport {

    @Autowired private WebApplicationContext wac;
    @Autowired private JdbcTemplate jdbcTemplate;

    private MockMvc mockMvc;
    private final UUID accountId = UUID.randomUUID();

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity())
                .build();
        admin(post("/api/v1/admin/accounts"),
                "{\"accountId\":\"" + accountId + "\",\"ownerName\":\"STATUS_USER\",\"baseCurrency\":\"KRW\"}")
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

    private ResultActions changeStatus(String action, String reason) throws Exception {
        String body = reason == null ? "{}" : "{\"reason\":\"" + reason + "\"}";
        return admin(post("/api/v1/admin/accounts/{id}/" + action, accountId), body);
    }

    private ResultActions cash(String kind, String key, int amount) throws Exception {
        return admin(post("/api/v1/admin/accounts/{id}/" + kind, accountId),
                "{\"idempotencyKey\":\"" + key + "\",\"currency\":\"KRW\",\"amount\":" + amount + "}");
    }

    @Test
    @DisplayName("정지된 계좌는 입금이 거부되고, 정지를 해제하면 다시 입금할 수 있다")
    void suspendBlocksAndActivateRestores() throws Exception {
        changeStatus("suspend", "FDS 이상 거래 탐지").andExpect(status().isOk());
        cash("deposits", "dep-1", 1000).andExpect(status().isUnprocessableContent());

        changeStatus("activate", "본인 확인 완료").andExpect(status().isOk());
        cash("deposits", "dep-2", 1000).andExpect(status().isOk());
    }

    @Test
    @DisplayName("정지에는 사유가 필요하다")
    void suspendRequiresReason() throws Exception {
        changeStatus("suspend", null).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("이미 정지된 계좌를 다시 정지하거나, 정상 계좌를 해제하면 422 다")
    void redundantTransitionsAreRejected() throws Exception {
        changeStatus("activate", "이미 정상").andExpect(status().isUnprocessableContent());
        changeStatus("suspend", "첫 정지").andExpect(status().isOk());
        changeStatus("suspend", "두 번째 정지").andExpect(status().isUnprocessableContent());
    }

    @Test
    @DisplayName("잔고가 남은 계좌는 해지할 수 없고, 잔고를 모두 출금하면 해지된다")
    void closeRequiresZeroBalance() throws Exception {
        cash("deposits", "dep-1", 5000).andExpect(status().isOk());

        changeStatus("close", "고객 요청").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ACCOUNT_HAS_BALANCE"));

        cash("withdrawals", "wd-1", 5000).andExpect(status().isOk());
        changeStatus("close", "고객 요청").andExpect(status().isOk());
    }

    @Test
    @DisplayName("해지된 계좌는 정지·해제·재해지가 모두 거부된다")
    void closedAccountCannotTransition() throws Exception {
        changeStatus("close", "고객 요청").andExpect(status().isOk());

        changeStatus("suspend", "사유").andExpect(status().isUnprocessableContent());
        changeStatus("activate", "사유").andExpect(status().isUnprocessableContent());
        changeStatus("close", "사유").andExpect(status().isUnprocessableContent());
    }

    @Test
    @DisplayName("상태 변경 이력에는 이전·이후 상태, 사유, 처리자가 순서대로 남는다")
    void historyRecordsEachChange() throws Exception {
        changeStatus("suspend", "FDS 이상 거래 탐지").andExpect(status().isOk());
        changeStatus("activate", "본인 확인 완료").andExpect(status().isOk());

        admin(get("/api/v1/admin/accounts/{id}/status-history", accountId), "")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].fromStatus").value("ACTIVE"))
                .andExpect(jsonPath("$[0].toStatus").value("SUSPENDED"))
                .andExpect(jsonPath("$[0].reason").value("FDS 이상 거래 탐지"))
                .andExpect(jsonPath("$[0].changedBy").value("admin-1"))
                .andExpect(jsonPath("$[1].toStatus").value("ACTIVE"));
    }

    @Test
    @DisplayName("거부된 상태 변경은 이력에 남지 않는다")
    void rejectedChangeLeavesNoHistory() throws Exception {
        changeStatus("activate", "이미 정상").andExpect(status().isUnprocessableContent());

        admin(get("/api/v1/admin/accounts/{id}/status-history", accountId), "")
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @DisplayName("계좌 소유자는 자기 계좌의 상태를 조회할 수 있고, 남의 계좌는 403 이다")
    void ownerCanReadAccount() throws Exception {
        changeStatus("suspend", "FDS 이상 거래 탐지").andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/accounts/{id}", accountId)
                        .header(HeaderPrincipalResolver.SUBJECT_HEADER, "user-1")
                        .header(HeaderPrincipalResolver.ACCOUNT_HEADER, accountId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId").value(accountId.toString()))
                .andExpect(jsonPath("$.baseCurrency").value("KRW"))
                .andExpect(jsonPath("$.status").value("SUSPENDED"));

        mockMvc.perform(get("/api/v1/accounts/{id}", accountId)
                        .header(HeaderPrincipalResolver.SUBJECT_HEADER, "user-2")
                        .header(HeaderPrincipalResolver.ACCOUNT_HEADER, UUID.randomUUID().toString()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("없는 계좌의 상태 변경은 404 다")
    void unknownAccountIsNotFound() throws Exception {
        admin(post("/api/v1/admin/accounts/{id}/suspend", UUID.randomUUID()), "{\"reason\":\"x\"}")
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("일반 사용자는 계좌를 정지할 수 없다")
    void suspendRequiresAdmin() throws Exception {
        mockMvc.perform(post("/api/v1/admin/accounts/{id}/suspend", accountId)
                        .header(HeaderPrincipalResolver.SUBJECT_HEADER, "user-1")
                        .header(HeaderPrincipalResolver.ACCOUNT_HEADER, accountId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"x\"}"))
                .andExpect(status().isForbidden());
    }
}
