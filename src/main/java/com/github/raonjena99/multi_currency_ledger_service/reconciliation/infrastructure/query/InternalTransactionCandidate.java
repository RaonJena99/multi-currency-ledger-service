package com.github.raonjena99.multi_currency_ledger_service.reconciliation.infrastructure.query;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.github.raonjena99.multi_currency_ledger_service.common.domain.Money;

/**
 * 외부 정산 내역과 대사할 내부 거래의 후보 데이터를 담는 불변 Record(레코드)입니다.
 */
public record InternalTransactionCandidate(
    UUID transactionId,
    OffsetDateTime transactedAt,
    String description,
    Money amount
) {}
