package com.github.raonjena99.multi_currency_ledger_service.transaction.presentation;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
import com.github.raonjena99.multi_currency_ledger_service.common.security.HeaderPrincipalResolver;
import com.github.raonjena99.multi_currency_ledger_service.transaction.domain.LedgerDeadLetter;
import com.github.raonjena99.multi_currency_ledger_service.transaction.infrastructure.LedgerDeadLetterRepository;

/**
 * 원장 데드레터 관리자 API 를 보안 필터와 전역 예외 처리기까지 포함한 실제 컨텍스트로 검증합니다.
 */
@DisplayName("원장 데드레터 관리자 API")
class LedgerDeadLetterAdminControllerTest extends IntegrationTestSupport {

    private static final String BASE = "/api/v1/admin/ledger/dead-letters";

    @Autowired private WebApplicationContext wac;
    @Autowired private LedgerDeadLetterRepository deadLetterRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private MockMvc mockMvc;
    private UUID accountId;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("TRUNCATE TABLE ledger_dead_letters");
        mockMvc = MockMvcBuilders.webAppContextSetup(wac)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity())
                .build();
        accountId = UUID.randomUUID();
        accountRepository.save(Account.open(accountId, "DLT_ADMIN_USER", "KRW"));
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.execute("TRUNCATE TABLE ledger_dead_letters, transaction_entries, transactions CASCADE");
        deleteTestAccounts();
    }

    private MockHttpServletRequestBuilder asAdmin(MockHttpServletRequestBuilder request) {
        return request
                .header(HeaderPrincipalResolver.SUBJECT_HEADER, "admin-1")
                .header(HeaderPrincipalResolver.ROLES_HEADER, "ADMIN");
    }

    private Long isolate(String payload) {
        return deadLetterRepository.save(
                LedgerDeadLetter.isolate("LedgerRecordingCommand", "boom", payload, "corr-1")).getId();
    }

    private String buyPayload(UUID tradeId) {
        return """
                {"tradeId":"%s","accountId":"%s","targetAssetCode":"BTC","targetAssetType":"CRYPTO",
                 "paymentCurrency":"KRW","baseCurrency":"KRW","tradeType":"BUY",
                 "quantity":{"amount":2,"assetType":"CRYPTO","currencyCode":"BTC"},
                 "unitPrice":100,"exchangeRate":100,"fiatToBaseRate":1,"averageCost":null,
                 "isStaleRate":false,"transactedAt":"%s"}
                """.formatted(tradeId, accountId, OffsetDateTime.now(ZoneOffset.UTC));
    }

    @Test
    @DisplayName("일반 사용자는 데드레터를 조회할 수 없다")
    void listRequiresAdmin() throws Exception {
        mockMvc.perform(get(BASE)
                        .header(HeaderPrincipalResolver.SUBJECT_HEADER, "user-1")
                        .header(HeaderPrincipalResolver.ACCOUNT_HEADER, accountId.toString()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("목록에는 미해결 건만 나오고 미해결 총 건수가 함께 온다")
    void listShowsOnlyUnresolved() throws Exception {
        Long unresolved = isolate(buyPayload(UUID.randomUUID()));
        Long resolved = isolate(buyPayload(UUID.randomUUID()));
        mockMvc.perform(asAdmin(post(BASE + "/{id}/resolve", resolved))).andExpect(status().isOk());

        mockMvc.perform(asAdmin(get(BASE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(1))
                .andExpect(jsonPath("$.deadLetters.length()").value(1))
                .andExpect(jsonPath("$.deadLetters[0].id").value(unresolved))
                .andExpect(jsonPath("$.deadLetters[0].correlationId").value("corr-1"));
    }

    @Test
    @DisplayName("재처리에 성공하면 기록된 거래 ID 를 돌려준다")
    void replayReturnsTransactionId() throws Exception {
        UUID tradeId = UUID.randomUUID();
        Long id = isolate(buyPayload(tradeId));

        mockMvc.perform(asAdmin(post(BASE + "/{id}/replay", id)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transactionId").value(tradeId.toString()));
    }

    @Test
    @DisplayName("없는 데드레터를 재처리하면 404 다")
    void replayOfUnknownIdIsNotFound() throws Exception {
        mockMvc.perform(asAdmin(post(BASE + "/{id}/replay", 999_999L)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LEDGER_DEAD_LETTER_NOT_FOUND"));
    }

    @Test
    @DisplayName("재처리가 실패하면 422 로 실패를 알린다")
    void replayFailureIsUnprocessable() throws Exception {
        Long id = isolate("{not-json");

        mockMvc.perform(asAdmin(post(BASE + "/{id}/replay", id)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("LEDGER_REPLAY_FAILED"));
    }

    @Test
    @DisplayName("이미 해결된 데드레터를 다시 해결하면 422 다")
    void resolvingTwiceIsUnprocessable() throws Exception {
        Long id = isolate(buyPayload(UUID.randomUUID()));
        mockMvc.perform(asAdmin(post(BASE + "/{id}/resolve", id))).andExpect(status().isOk());

        mockMvc.perform(asAdmin(post(BASE + "/{id}/resolve", id)))
                .andExpect(status().isUnprocessableContent());
    }
}
