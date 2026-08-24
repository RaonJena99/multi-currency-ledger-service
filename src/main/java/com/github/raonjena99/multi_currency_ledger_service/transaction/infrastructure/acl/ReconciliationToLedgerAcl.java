package com.github.raonjena99.multi_currency_ledger_service.transaction.infrastructure.acl;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.slf4j.MDC;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.github.raonjena99.multi_currency_ledger_service.common.domain.Money;
import com.github.raonjena99.multi_currency_ledger_service.common.outbox.OutboxEvent;
import com.github.raonjena99.multi_currency_ledger_service.common.outbox.OutboxRepository;
import com.github.raonjena99.multi_currency_ledger_service.common.telemetry.CorrelationIdFilter;
import com.github.raonjena99.multi_currency_ledger_service.reconciliation.domain.event.ReconciliationFeeAdjustedEvent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.json.JsonMapper;

/**
 * 정산(Reconciliation) 이벤트와 원장(Ledger) 간의 부패 방지 계층(ACL)을 담당하는 ReconciliationToLedgerAcl 클래스입니다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReconciliationToLedgerAcl {

    private final OutboxRepository outboxRepository;
    private final JsonMapper jsonMapper;

    record LedgerRecordingPayload(
        UUID settlementId,
        UUID accountId,
        String targetAssetCode,
        String paymentCurrency,
        String baseCurrency,
        String tradeType,
        Money quantity,
        BigDecimal unitPrice,
        BigDecimal exchangeRate,
        BigDecimal fiatToBaseRate,
        BigDecimal averageCost,
        boolean isStaleRate,
        OffsetDateTime transactedAt
    ) {}

    /**
     * 정산 수수료 조정 이벤트를 처리하여 원장에 기록합니다.
     *
     * @param event 정산 수수료 조정 이벤트 객체
     */
    @EventListener
    public void handle(ReconciliationFeeAdjustedEvent event) {
        Money fee = event.feeDifference();
        String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);

        try {
            LedgerRecordingPayload payload = new LedgerRecordingPayload(
                event.settlementId(),
                // 수수료 오차는 해당 거래의 실제 계좌에 귀속되어야 한다.
                // 시스템 수수료 계정을 하드코딩하면 보정액이 고객에게 도달하지 않는다.
                event.accountId(),
                // 자산 코드 자리에는 자산 '코드'가 들어가야 한다. AssetType 이름(FIAT 등)이 아니다.
                fee.getCurrencyCode(),
                fee.getCurrencyCode(),
                fee.getCurrencyCode(),
                "FEE_ADJUSTMENT",
                fee,
                BigDecimal.ONE,
                BigDecimal.ONE,
                BigDecimal.ONE,
                BigDecimal.ZERO,
                false,
                event.occurredAt()
            );

            OutboxEvent outboxEvent = new OutboxEvent(
                "Ledger", event.accountId().toString(), "LedgerRecordingCommand",
                jsonMapper.writeValueAsString(payload), correlationId
            );

            // 트랜잭션 내에서 아웃박스 테이블에 기록 완료
            outboxRepository.save(outboxEvent);
            log.info("Reconciliation ACL: Persisted OutboxEvent for SettlementID: {}", event.settlementId());
        } catch (Exception e) {
            log.error("Failed to serialize ReconciliationFeeAdjustedEvent to Outbox", e);
            throw new com.github.raonjena99.multi_currency_ledger_service.common.exception.EventPublishingException(
                    "Outbox serialization error", e);
        }
    }
}
