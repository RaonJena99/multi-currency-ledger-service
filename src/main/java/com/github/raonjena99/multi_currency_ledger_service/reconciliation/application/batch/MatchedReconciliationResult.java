package com.github.raonjena99.multi_currency_ledger_service.reconciliation.application.batch;

import java.util.UUID;

import com.github.raonjena99.multi_currency_ledger_service.common.domain.Money;
import com.github.raonjena99.multi_currency_ledger_service.reconciliation.domain.ExternalSettlement;

public record MatchedReconciliationResult(
    ExternalSettlement externalSettlement,
    UUID matchedTransactionId,
    Money feeDifference
) {}
