package com.github.raonjena99.multi_currency_ledger_service.regression;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.github.raonjena99.multi_currency_ledger_service.IntegrationTestSupport;
import com.github.raonjena99.multi_currency_ledger_service.common.security.HeaderPrincipalResolver;

/**
 * 계좌 접근 통제를 검증합니다.
 *
 * <p>인증만 붙이고 소유권을 확인하지 않으면 계좌 ID 를 경로에 담아 보내는 것만으로 남의 계좌를
 * 거래할 수 있습니다. 인증과 소유권 검증은 함께 있어야 의미가 있습니다.
 */
@DisplayName("회귀 테스트: 계좌 접근 통제")
class AccountAccessControlTest extends IntegrationTestSupport {

    @Autowired private WebApplicationContext wac;

    private MockMvc mockMvc;

    private final UUID myAccount = UUID.randomUUID();
    private final UUID otherAccount = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    private String tradeBody() {
        return "{\"idempotencyKey\":\"k-" + UUID.randomUUID() + "\",\"targetAssetCode\":\"BTC\","
                + "\"targetAssetType\":\"CRYPTO\",\"paymentCurrency\":\"KRW\","
                + "\"quantity\":1,\"unitPrice\":1}";
    }

    private MockHttpServletRequestBuilder asOwnerOf(UUID principalAccount, UUID targetAccount) {
        return post("/api/v1/accounts/{accountId}/trades/buy", targetAccount)
                .header(HeaderPrincipalResolver.SUBJECT_HEADER, "user-" + principalAccount)
                .header(HeaderPrincipalResolver.ACCOUNT_HEADER, principalAccount.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(tradeBody());
    }

    @Test
    @DisplayName("인증 정보가 없으면 401 이다")
    void anonymous_request_is_unauthorized() throws Exception {
        mockMvc.perform(post("/api/v1/accounts/{accountId}/trades/buy", myAccount)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tradeBody()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("남의 계좌로 거래하려 하면 403 이다")
    void trading_someone_elses_account_is_forbidden() throws Exception {
        mockMvc.perform(asOwnerOf(myAccount, otherAccount))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("남의 포트폴리오를 조회하려 하면 403 이다")
    void reading_someone_elses_portfolio_is_forbidden() throws Exception {
        mockMvc.perform(get("/api/v1/portfolios/{accountId}", otherAccount)
                        .header(HeaderPrincipalResolver.SUBJECT_HEADER, "user-1")
                        .header(HeaderPrincipalResolver.ACCOUNT_HEADER, myAccount.toString()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("자기 계좌 요청은 접근 통제를 통과한다 (이후 도메인 검증으로 진행)")
    void own_account_passes_access_control() throws Exception {
        // 계좌 자체는 없으므로 404 가 되지만, 401/403 이 아니라는 점이 핵심이다.
        mockMvc.perform(asOwnerOf(myAccount, myAccount))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("관리자 API 는 일반 사용자에게 403 이다")
    void admin_api_requires_admin_role() throws Exception {
        mockMvc.perform(post("/api/v1/admin/reconciliations/dead-letters/{id}/resolve", 1L)
                        .header(HeaderPrincipalResolver.SUBJECT_HEADER, "user-1")
                        .header(HeaderPrincipalResolver.ACCOUNT_HEADER, myAccount.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"internalTransactionId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("관리자는 다른 계좌에도 접근할 수 있다")
    void admin_can_access_any_account() throws Exception {
        mockMvc.perform(post("/api/v1/accounts/{accountId}/trades/buy", otherAccount)
                        .header(HeaderPrincipalResolver.SUBJECT_HEADER, "admin-1")
                        .header(HeaderPrincipalResolver.ROLES_HEADER, "ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tradeBody()))
                .andExpect(status().isNotFound());
    }
}
