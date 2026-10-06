package com.github.raonjena99.multi_currency_ledger_service.reconciliation.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.github.raonjena99.multi_currency_ledger_service.IntegrationTestSupport;
import com.github.raonjena99.multi_currency_ledger_service.common.outbox.OutboxRelayWorker;
import com.github.raonjena99.multi_currency_ledger_service.common.security.HeaderPrincipalResolver;
import com.github.raonjena99.multi_currency_ledger_service.reconciliation.application.ingestion.SettlementCsvIngestionService;
import com.github.raonjena99.multi_currency_ledger_service.reconciliation.application.ingestion.SettlementRecorder;

/**
 * PG 정산 내역 CSV 업로드부터 대사 배치 실행, 결과 확인까지를 API 만으로 수행할 수 있는지 검증합니다.
 *
 * <p>개인이 접속할 수 있는 PG 정산 API 가 없어 대사 배치에 넣을 데이터가 없었습니다. 실무에서 흔한
 * "PG 가 정산 내역을 파일로 내려주는" 방식으로 적재 경로를 엽니다.
 */
@DisplayName("PG 정산 CSV 업로드와 대사 실행 API")
class SettlementCsvUploadApiTest extends IntegrationTestSupport {

    private static final String HEADER = "transactionId,currency,amount,fee,status,settledAt\n";

    @Autowired private WebApplicationContext wac;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private SettlementRecorder settlementRecorder;

    // 수수료 보정이 생기면 아웃박스로 발행되는데, 이 테스트는 대사 결과만 본다.
    @MockitoBean private OutboxRelayWorker outboxRelayWorker;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS external_settlement_default PARTITION OF external_settlement DEFAULT");
        mockMvc = MockMvcBuilders.webAppContextSetup(wac)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.execute("TRUNCATE TABLE reconciliation_dead_letter, settlement_match, external_settlement, "
                + "outbox_events, transaction_entries, transactions CASCADE");
        deleteTestAccounts();
    }

    private ResultActions upload(String csv) throws Exception {
        return mockMvc.perform(multipart("/api/v1/admin/reconciliations/settlements/upload")
                .file(new MockMultipartFile("file", "settlements.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8)))
                .header(HeaderPrincipalResolver.SUBJECT_HEADER, "admin-1")
                .header(HeaderPrincipalResolver.ROLES_HEADER, "ADMIN"));
    }

    private MockHttpServletRequestBuilder asAdmin(MockHttpServletRequestBuilder request) {
        return request.header(HeaderPrincipalResolver.SUBJECT_HEADER, "admin-1")
                .header(HeaderPrincipalResolver.ROLES_HEADER, "ADMIN");
    }

    private BigDecimal settlementAmount(String referenceId) {
        return jdbcTemplate.queryForObject(
                "SELECT amount FROM external_settlement WHERE external_reference_id = ?", BigDecimal.class, referenceId);
    }

    @Test
    @DisplayName("유효한 행을 적재하고, 금액은 수수료를 뺀 실수령액으로 저장한다")
    void ingestsValidRows() throws Exception {
        upload(HEADER
                + "PG-1,KRW,1000,25,PAID,2026-06-15T10:00:00Z\n"
                + "PG-2,USD,10.50,0,PAID,2026-06-16T10:00:00Z\n")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalRows").value(2))
                .andExpect(jsonPath("$.ingested").value(2))
                .andExpect(jsonPath("$.duplicates").value(0))
                .andExpect(jsonPath("$.failures.length()").value(0));

        assertThat(settlementAmount("PG-1")).isEqualByComparingTo("975");
        assertThat(settlementAmount("PG-2")).isEqualByComparingTo("10.50");
    }

    @Test
    @DisplayName("같은 거래 ID 를 다시 올리면 중복으로 세고 적재하지 않는다")
    void reuploadCountsDuplicates() throws Exception {
        String csv = HEADER + "PG-1,KRW,1000,0,PAID,2026-06-15T10:00:00Z\n";
        upload(csv).andExpect(status().isOk());

        upload(csv)
                .andExpect(jsonPath("$.ingested").value(0))
                .andExpect(jsonPath("$.duplicates").value(1));
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM external_settlement", Integer.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("잘못된 행은 행 번호와 사유를 알려주고, 나머지 행은 계속 적재한다")
    void reportsInvalidRowsAndKeepsGoing() throws Exception {
        upload(HEADER
                + "PG-OK,KRW,1000,0,PAID,2026-06-15T10:00:00Z\n"   // 2행
                + "PG-AMOUNT,KRW,abc,0,PAID,2026-06-15T10:00:00Z\n" // 3행
                + "PG-CURRENCY,BTC,1000,0,PAID,2026-06-15T10:00:00Z\n" // 4행
                + "PG-SHORT,KRW,1000\n"                              // 5행
                + "PG-DATE,KRW,1000,0,PAID,2026-06-15\n"             // 6행
                + "PG-FEE,KRW,1000,2000,PAID,2026-06-15T10:00:00Z\n") // 7행
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalRows").value(6))
                .andExpect(jsonPath("$.ingested").value(1))
                .andExpect(jsonPath("$.failures.length()").value(5))
                .andExpect(jsonPath("$.failures[0].line").value(3))
                .andExpect(jsonPath("$.failures[0].transactionId").value("PG-AMOUNT"))
                .andExpect(jsonPath("$.failures[1].line").value(4))
                .andExpect(jsonPath("$.failures[2].line").value(5))
                .andExpect(jsonPath("$.failures[3].line").value(6))
                .andExpect(jsonPath("$.failures[4].line").value(7));
    }

    @Test
    @DisplayName("머리글이 약속한 형식이 아니면 파일 전체를 거부한다")
    void rejectsUnexpectedHeader() throws Exception {
        upload("id,amount\nPG-1,1000\n").andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("행 수가 상한을 넘으면 파일 전체를 거부한다")
    void rejectsTooManyRows() {
        SettlementCsvIngestionService smallLimit = new SettlementCsvIngestionService(settlementRecorder, 2);
        String csv = HEADER
                + "PG-1,KRW,1000,0,PAID,2026-06-15T10:00:00Z\n"
                + "PG-2,KRW,1000,0,PAID,2026-06-15T10:00:00Z\n"
                + "PG-3,KRW,1000,0,PAID,2026-06-15T10:00:00Z\n";

        assertThatThrownBy(() -> smallLimit.ingest(new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM external_settlement", Integer.class)).isZero();
    }

    @Test
    @DisplayName("업로드 → 대사 배치 실행 → 매칭·데드레터 확인을 API 만으로 할 수 있다")
    void uploadRunAndInspectThroughApi() throws Exception {
        // 내부 거래: 6월 15일 1,000 KRW 매도 대금
        UUID accountId = UUID.randomUUID();
        UUID transactionId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO accounts (id, owner_name, status, base_currency, created_at, updated_at) "
                + "VALUES (?, 'CSV_USER', 'ACTIVE', 'KRW', now(), now())", accountId);
        jdbcTemplate.update("INSERT INTO transactions (id, transaction_type, transacted_at, description) "
                + "VALUES (?, 'SELL', '2026-06-15 10:00:00+00', 'PG settlement')", transactionId);
        jdbcTemplate.update("INSERT INTO transaction_entries (transaction_id, account_id, entry_type, asset_code, "
                + "quantity_asset_type, quantity, quantity_currency, unit_price, amount, amount_asset_type, "
                + "amount_currency, realized_pnl, realized_pnl_asset_type, realized_pnl_currency) "
                + "VALUES (?, ?, 'CREDIT', 'KRW', 'FIAT', 1000, 'KRW', 1, 1000, 'FIAT', 'KRW', 0, 'FIAT', 'KRW')",
                transactionId, accountId);

        // 정산: 내부 거래와 맞는 건 1개, 맞는 거래가 없는 건 1개
        upload(HEADER
                + "PG-MATCH,KRW,1000,0,PAID,2026-06-15T10:00:00Z\n"
                + "PG-GHOST,KRW,99999,0,PAID,2026-06-25T10:00:00Z\n")
                .andExpect(jsonPath("$.ingested").value(2));

        mockMvc.perform(asAdmin(post("/api/v1/admin/reconciliations/jobs").param("month", "2026-06")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.matched").value(1))
                .andExpect(jsonPath("$.deadLettered").value(1));

        mockMvc.perform(asAdmin(get("/api/v1/admin/reconciliations/dead-letters")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(1))
                .andExpect(jsonPath("$.deadLetters[0].externalReferenceId").value("PG-GHOST"));
    }

    @Test
    @DisplayName("일반 사용자는 정산 내역을 올릴 수 없다")
    void uploadRequiresAdmin() throws Exception {
        mockMvc.perform(multipart("/api/v1/admin/reconciliations/settlements/upload")
                        .file(new MockMultipartFile("file", "s.csv", "text/csv", HEADER.getBytes(StandardCharsets.UTF_8)))
                        .header(HeaderPrincipalResolver.SUBJECT_HEADER, "user-1"))
                .andExpect(status().isForbidden());
    }
}
