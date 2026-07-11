package com.github.raonjena99.multi_currency_ledger_service.reconciliation.infrastructure.adapter;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class PgSettlementAdapterTest {

    @Test
    void fetchSettlement() {
        RestClient restClient = mock(RestClient.class);
        RestClient.RequestHeadersUriSpec uriSpec = mock(RestClient.RequestHeadersUriSpec.class);
        RestClient.ResponseSpec responseSpec = mock(RestClient.ResponseSpec.class);

        when(restClient.get()).thenReturn(uriSpec);
        when(uriSpec.uri(anyString(), anyString())).thenReturn(uriSpec);
        when(uriSpec.retrieve()).thenReturn(responseSpec);
        
        ExternalSettlementDto dto = new ExternalSettlementDto("tx1", "KRW", BigDecimal.TEN, BigDecimal.ONE, "OK", OffsetDateTime.now());
        when(responseSpec.body(ExternalSettlementDto.class)).thenReturn(dto);

        PgSettlementAdapter adapter = new PgSettlementAdapter(restClient);
        ExternalSettlementDto result = adapter.fetchSettlement("tx1");

        org.assertj.core.api.Assertions.assertThat(result).isNotNull();
    }
}
