package com.github.raonjena99.multi_currency_ledger_service.reconciliation.domain.event;

import java.util.UUID;

import com.github.raonjena99.multi_currency_ledger_service.common.domain.Money;

public record ReconciliationFeeAdjustedEvent(
    UUID settlementId,
    Money feeDifference
) {}
