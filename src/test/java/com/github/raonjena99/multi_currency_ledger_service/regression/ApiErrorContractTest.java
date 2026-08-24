package com.github.raonjena99.multi_currency_ledger_service.regression;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.github.raonjena99.multi_currency_ledger_service.IntegrationTestSupport;
import com.github.raonjena99.multi_currency_ledger_service.common.security.HeaderPrincipalResolver;

/**
 * 클라이언트 오류가 4xx 로, 서버 오류가 5xx 로 나가는지 검증합니다.
 *
 * <p>{@code @ExceptionHandler(Exception.class)} 캐치올만 두고 {@code ResponseEntityExceptionHandler}
 * 를 상속하지 않으면, Spring MVC 의 표준 4xx 예외를 기본 리졸버보다 먼저 가로채
 * <b>모든 클라이언트 오류가 500 으로 나갑니다.</b> 컨트롤러의 {@code @Valid} 도 함께 무력화됩니다.
 */
@DisplayName("회귀 테스트: API 오류 상태 코드 계약")
class ApiErrorContractTest extends IntegrationTestSupport {

    @Autowired private WebApplicationContext wac;

    private MockMvc mockMvc;
    private final UUID accountId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    /** 인증 헤더를 붙인 요청 빌더. 소유권 검증까지 통과하도록 계좌 ID 를 함께 넣는다. */
    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder authedPost(String url, Object... vars) {
        return post(url, vars)
                .header(HeaderPrincipalResolver.SUBJECT_HEADER, "user-1")
                .header(HeaderPrincipalResolver.ACCOUNT_HEADER, accountId.toString());
    }

    @Test
    @DisplayName("@Valid 위반은 400 으로 나간다")
    void validation_failure_returns_400() throws Exception {
        mockMvc.perform(authedPost("/api/v1/accounts/{accountId}/trades/buy", accountId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idempotencyKey\":\"\",\"targetAssetCode\":\"\",\"quantity\":-5}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }

    @Test
    @DisplayName("깨진 JSON 은 400 으로 나간다")
    void malformed_json_returns_400() throws Exception {
        mockMvc.perform(authedPost("/api/v1/accounts/{accountId}/trades/buy", accountId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ this is not json "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }

    @Test
    @DisplayName("경로 변수 타입 오류는 400 으로 나간다")
    void bad_path_variable_returns_400() throws Exception {
        mockMvc.perform(post("/api/v1/accounts/not-a-uuid/trades/buy")
                        .header(HeaderPrincipalResolver.SUBJECT_HEADER, "user-1")
                        .header(HeaderPrincipalResolver.ACCOUNT_HEADER, accountId.toString())
                        .header(HeaderPrincipalResolver.ROLES_HEADER, "ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idempotencyKey\":\"k\",\"targetAssetCode\":\"BTC\","
                                + "\"targetAssetType\":\"CRYPTO\",\"paymentCurrency\":\"KRW\","
                                + "\"quantity\":1,\"unitPrice\":1}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("지원하지 않는 HTTP 메서드는 405 로 나간다")
    void wrong_method_returns_405() throws Exception {
        mockMvc.perform(get("/api/v1/accounts/{accountId}/trades/buy", accountId)
                        .header(HeaderPrincipalResolver.SUBJECT_HEADER, "user-1")
                        .header(HeaderPrincipalResolver.ACCOUNT_HEADER, accountId.toString()))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    @DisplayName("존재하지 않는 계좌 거래 요청은 404 로 나간다")
    void unknown_account_returns_404() throws Exception {
        mockMvc.perform(authedPost("/api/v1/accounts/{accountId}/trades/buy", accountId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idempotencyKey\":\"k-" + UUID.randomUUID() + "\",\"targetAssetCode\":\"BTC\","
                                + "\"targetAssetType\":\"CRYPTO\",\"paymentCurrency\":\"KRW\","
                                + "\"quantity\":1,\"unitPrice\":1}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"));
    }
}
