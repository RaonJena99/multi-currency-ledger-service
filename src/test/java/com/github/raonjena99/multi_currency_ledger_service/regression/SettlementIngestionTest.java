package com.github.raonjena99.multi_currency_ledger_service.regression;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.github.raonjena99.multi_currency_ledger_service.IntegrationTestSupport;
import com.github.raonjena99.multi_currency_ledger_service.common.model.SettlementStatus;
import com.github.raonjena99.multi_currency_ledger_service.reconciliation.application.ingestion.SettlementIngestionService;
import com.github.raonjena99.multi_currency_ledger_service.reconciliation.infrastructure.ExternalSettlementRepository;
import com.github.raonjena99.multi_currency_ledger_service.reconciliation.infrastructure.adapter.ExternalSettlementDto;
import com.github.raonjena99.multi_currency_ledger_service.reconciliation.infrastructure.adapter.PgSettlementAdapter;

/**
 * 대사 배치가 읽을 정산 데이터를 실제로 적재할 수 있는지 검증합니다.
 *
 * <p>이 경로가 없으면 룰 엔진과 배치 잡 정의가 아무리 완성돼 있어도 입력이 없어 프로덕션에서
 * 대사가 한 건도 이루어지지 않습니다. 적재 서비스가 도입되기 전에는
 * {@code ExternalSettlement.create} 를 호출하는 코드가 테스트에만 존재했습니다.
 */
@DisplayName("회귀 테스트: 정산 데이터 적재 경로")
class SettlementIngestionTest extends IntegrationTestSupport {

    @Autowired private SettlementIngestionService ingestionService;
    @Autowired private ExternalSettlementRepository settlementRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private PgSettlementAdapter pgSettlementAdapter;

    @AfterEach
    void tearDown() {
        jdbcTemplate.execute("TRUNCATE TABLE external_settlement CASCADE");
    }

    private void stub(String txId, String amount, String fee, String currency) {
        org.mockito.Mockito.when(pgSettlementAdapter.fetchSettlement(txId))
                .thenReturn(new ExternalSettlementDto(txId, currency, new BigDecimal(amount),
                        new BigDecimal(fee), "PAID", OffsetDateTime.now()));
    }

    @Test
    @DisplayName("PG 응답을 정산 내역으로 적재하고 수수료를 차감한 실수령액을 기록한다")
    void ingests_pg_settlement_with_net_amount() {
        stub("PG-1", "10000", "300", "KRW");

        int ingested = ingestionService.ingest(List.of("PG-1"));

        assertThat(ingested).isEqualTo(1);

        var saved = settlementRepository.findByInstitutionCodeAndExternalReferenceId("PG", "PG-1");
        assertThat(saved).isPresent();
        assertThat(saved.get().getAmount().getAmount())
                .as("10000 - 300 = 9700 (수수료 차감 실수령액)")
                .isEqualByComparingTo("9700");
        assertThat(saved.get().getStatus()).isEqualTo(SettlementStatus.PENDING);
    }

    @Test
    @DisplayName("이미 적재된 건은 다시 적재하지 않는다")
    void skips_already_ingested() {
        stub("PG-2", "5000", "0", "KRW");

        assertThat(ingestionService.ingest(List.of("PG-2"))).isEqualTo(1);
        assertThat(ingestionService.ingest(List.of("PG-2")))
                .as("중복 적재는 0 건이어야 한다")
                .isZero();
    }

    @Test
    @DisplayName("한 건이 실패해도 나머지 적재는 계속된다")
    void one_failure_does_not_stop_the_batch() {
        stub("PG-3", "1000", "0", "KRW");
        org.mockito.Mockito.when(pgSettlementAdapter.fetchSettlement("PG-BROKEN"))
                .thenThrow(new RuntimeException("PG API down"));
        stub("PG-4", "2000", "0", "KRW");

        int ingested = ingestionService.ingest(List.of("PG-3", "PG-BROKEN", "PG-4"));

        assertThat(ingested).isEqualTo(2);
        assertThat(settlementRepository.findByInstitutionCodeAndExternalReferenceId("PG", "PG-3")).isPresent();
        assertThat(settlementRepository.findByInstitutionCodeAndExternalReferenceId("PG", "PG-4")).isPresent();
    }

    @Test
    @DisplayName("PG 응답이 비어 있으면 적재하지 않는다")
    void empty_response_is_skipped() {
        org.mockito.Mockito.when(pgSettlementAdapter.fetchSettlement("PG-EMPTY")).thenReturn(null);

        assertThat(ingestionService.ingest(List.of("PG-EMPTY"))).isZero();
    }
}
