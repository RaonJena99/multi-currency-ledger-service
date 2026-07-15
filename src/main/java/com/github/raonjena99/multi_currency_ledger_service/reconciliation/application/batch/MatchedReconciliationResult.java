package com.github.raonjena99.multi_currency_ledger_service.reconciliation.application.batch;

import java.util.UUID;

import com.github.raonjena99.multi_currency_ledger_service.common.domain.Money;
import com.github.raonjena99.multi_currency_ledger_service.reconciliation.domain.ExternalSettlement;

/**
 * 매칭에 성공한 대사 결과 데이터를 담는 Record(레코드)입니다.
 * 외부 정산 내역과 매칭된 내부 거래 ID, 그리고 수수료 차액 정보를 포함합니다.
 */
public record MatchedReconciliationResult(
    ExternalSettlement externalSettlement,
    UUID matchedTransactionId,
    Money feeDifference
) {}
