package com.github.raonjena99.multi_currency_ledger_service.portfolio.presentation;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import com.github.raonjena99.multi_currency_ledger_service.portfolio.application.PortfolioQueryService;
import com.github.raonjena99.multi_currency_ledger_service.portfolio.application.dto.PortfolioSummaryResponse;

@ExtendWith(MockitoExtension.class)
class PortfolioControllerTest {
    @Mock private PortfolioQueryService portfolioQueryService;
    @Mock private com.github.raonjena99.multi_currency_ledger_service.common.security.AccountOwnershipGuard ownershipGuard;
    @InjectMocks private PortfolioController controller;

    @Test
    void getPortfolioSummary() {
        UUID id = UUID.randomUUID();
        PortfolioSummaryResponse mockResponse = org.mockito.Mockito.mock(PortfolioSummaryResponse.class);
        when(portfolioQueryService.getPortfolioSummary(id)).thenReturn(mockResponse);

        ResponseEntity<PortfolioSummaryResponse> res = controller.getPortfolioSummary(id);
        
        verify(ownershipGuard).requireOwnership(id);
        verify(portfolioQueryService).getPortfolioSummary(id);
        org.assertj.core.api.Assertions.assertThat(res.getBody()).isEqualTo(mockResponse);
        org.assertj.core.api.Assertions.assertThat(res.getStatusCode().is2xxSuccessful()).isTrue();
    }
}
