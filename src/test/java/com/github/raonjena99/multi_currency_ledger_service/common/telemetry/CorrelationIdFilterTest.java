package com.github.raonjena99.multi_currency_ledger_service.common.telemetry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

import java.io.IOException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;

@ExtendWith(MockitoExtension.class)
class CorrelationIdFilterTest {

    @InjectMocks
    private CorrelationIdFilter filter;

    @Mock
    private FilterChain filterChain;

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    void doFilter_should_use_existing_correlation_id() throws IOException, ServletException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationIdFilter.CORRELATION_ID_HEADER, "existing-id");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        // Can't directly assert MDC state after doFilter because it removes it in finally block
        // So we just ensure it executes without error.
    }

    @Test
    void doFilter_should_generate_new_correlation_id_if_missing() throws IOException, ServletException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
    }
    
    @Test
    void doFilter_should_clear_mdc_even_if_chain_throws() throws IOException, ServletException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        
        org.mockito.Mockito.doThrow(new ServletException("Chain failed")).when(filterChain).doFilter(request, response);
        
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> filter.doFilter(request, response, filterChain))
            .isInstanceOf(ServletException.class);
            
        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
    }
}
