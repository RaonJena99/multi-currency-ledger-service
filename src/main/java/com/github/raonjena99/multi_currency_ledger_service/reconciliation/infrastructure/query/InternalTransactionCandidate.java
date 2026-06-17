package com.github.raonjena99.multi_currency_ledger_service.reconciliation.infrastructure.query;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.github.raonjena99.multi_currency_ledger_service.common.domain.Money;

public record InternalTransactionCandidate(
    UUID transactionId,
    OffsetDateTime transactedAt,
    String description,
    Money amount
) {}
