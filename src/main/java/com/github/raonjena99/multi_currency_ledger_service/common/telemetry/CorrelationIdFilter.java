package com.github.raonjena99.multi_currency_ledger_service.common.telemetry;

import java.io.IOException;
import java.util.UUID;

import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter implements Filter{

    public static final String CORRELATION_ID_HEADER = "X-Correlation-Id";
    public static final String MDC_KEY = "correlation_id";

    /** 아웃박스 correlation_id 컬럼 길이(100)와 일치시킨다. */
    private static final int MAX_CORRELATION_ID_LENGTH = 100;

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest httpRequest = (HttpServletRequest) request;
        String correlationId = sanitize(httpRequest.getHeader(CORRELATION_ID_HEADER));

        if (!StringUtils.hasText(correlationId)) {
            correlationId = UUID.randomUUID().toString();
        }
        try {
            MDC.put(MDC_KEY, correlationId);
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    /**
     * 클라이언트가 보낸 상관관계 ID 를 로그/Kafka 헤더에 싣기 전에 정제합니다.
     *
     * <p>개행 등 제어 문자를 그대로 반영하면 로그 위조(log forging)가 가능하고, 무제한 길이는
     * 로그 파이프라인과 아웃박스 컬럼(100자)을 오염시킵니다. 허용 문자 밖이면 새로 발급합니다.
     */
    private String sanitize(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.length() > MAX_CORRELATION_ID_LENGTH || !trimmed.matches("[A-Za-z0-9._:-]+")) {
            return null;
        }
        return trimmed;
    }
}
