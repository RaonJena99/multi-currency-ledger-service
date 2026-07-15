package com.github.raonjena99.multi_currency_ledger_service.reconciliation.infrastructure.adapter;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * PG사 외부 API 응답을 매핑하기 위한 불변 데이터 객체(DTO, Data Transfer Object)입니다.
 */
public record ExternalSettlementDto(
    String transactionId,
    String currency,
    BigDecimal amount,
    BigDecimal fee,
    String status,
    OffsetDateTime settledAt
) {}
