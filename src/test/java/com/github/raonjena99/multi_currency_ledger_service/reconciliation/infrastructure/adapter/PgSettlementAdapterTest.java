package com.github.raonjena99.multi_currency_ledger_service.reconciliation.infrastructure.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class PgSettlementAdapterTest {

    private PgSettlementAdapter adapter;
    private MockRestServiceServer mockServer;
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        mockServer = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.build();
        meterRegistry = new SimpleMeterRegistry();
        
        adapter = new PgSettlementAdapter(restClient, meterRegistry);
    }

    @Test
    void fetchSettlement_should_return_dto_on_success() {
        String txId = "tx-123";
        String jsonResponse = """
            {
                "transactionId": "tx-123",
                "currency": "KRW",
                "amount": 10000,
                "fee": 100,
                "status": "COMPLETED",
                "settledAt": "2023-10-10T10:10:10Z"
            }
            """;

        mockServer.expect(requestTo("/api/v1/pg/settlements/" + txId))
                .andRespond(withSuccess(jsonResponse, MediaType.APPLICATION_JSON));

        ExternalSettlementDto dto = adapter.fetchSettlement(txId);

        assertThat(dto.transactionId()).isEqualTo("tx-123");
        assertThat(dto.currency()).isEqualTo("KRW");
        assertThat(dto.amount()).isEqualByComparingTo(new BigDecimal("10000"));
        assertThat(dto.fee()).isEqualByComparingTo(new BigDecimal("100"));
        assertThat(dto.status()).isEqualTo("COMPLETED");
        assertThat(dto.settledAt()).isEqualTo(OffsetDateTime.parse("2023-10-10T10:10:10Z"));
    }

    @Test
    void fallbackSettlement_should_increment_metric_and_throw_exception() {
        String txId = "tx-123";
        Throwable t = new RuntimeException("API error");

        assertThatThrownBy(() -> adapter.fallbackSettlement(txId, t))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("PG API 호출 실패로 인한 Fallback")
            .hasCause(t);

        double count = meterRegistry.counter("external.api.fallback.count", "api", "pgSettlement").count();
        assertThat(count).isEqualTo(1.0);
    }
}
