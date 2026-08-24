package com.github.raonjena99.multi_currency_ledger_service.account.infrastructure.acl;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.slf4j.MDC;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.github.raonjena99.multi_currency_ledger_service.account.domain.event.TradeExecutedEvent;
import com.github.raonjena99.multi_currency_ledger_service.common.domain.Money;
import com.github.raonjena99.multi_currency_ledger_service.common.model.AssetType;
import com.github.raonjena99.multi_currency_ledger_service.common.outbox.OutboxEvent;
import com.github.raonjena99.multi_currency_ledger_service.common.outbox.OutboxRepository;
import com.github.raonjena99.multi_currency_ledger_service.common.telemetry.CorrelationIdFilter;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.json.JsonMapper;

@Slf4j
@Component
@RequiredArgsConstructor
public class AccountOutboxAcl {

    private final OutboxRepository outboxRepository;
    private final JsonMapper jsonMapper;

    // 모듈 간 강결합(Modulith Violation) 방지를 위해 내부 DTO 레코드 선언
    record LedgerRecordingPayload(
        UUID tradeId,
        UUID accountId,
        String targetAssetCode,
        AssetType targetAssetType,
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
     * TradeExecutedEvent(거래 실행 이벤트)를 수신하여 OutboxEvent(아웃박스 이벤트)로 변환 후 저장합니다.
     *
     * {@code @EventListener} 를 사용하므로 거래 트랜잭션과 동일한 트랜잭션에서 아웃박스 행이
     * 저장됩니다. 직렬화가 실패하면 거래 전체가 롤백되어야 하므로 예외를 삼키지 않습니다.
     *
     * @param externalEvent 거래 실행 이벤트
     */
    @EventListener
    public void persistOutboxEvent(TradeExecutedEvent externalEvent) {

        log.info("Account ACL: Persisting OutboxEvent for TradeID: {}", externalEvent.tradeId());

        try {
            // MDC 키는 CorrelationIdFilter 가 심는 값과 반드시 같아야 한다.
            // 문자열 리터럴을 따로 쓰면 조용히 null 이 되어 추적 체인이 끊긴다.
            String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);

            // 내부 DTO로 변환
            LedgerRecordingPayload payload = new LedgerRecordingPayload(
                externalEvent.tradeId(),
                externalEvent.accountId(),
                externalEvent.assetCode(),
                externalEvent.assetType(),
                externalEvent.fiatCode(),
                externalEvent.baseCurrency(),
                externalEvent.tradeType().name(),
                Money.of(externalEvent.quantity().toPlainString(), externalEvent.assetType(), externalEvent.assetCode()),
                externalEvent.unitPrice(),
                externalEvent.exchangeRate(),
                externalEvent.fiatToBaseRate(),
                externalEvent.averageCost(),
                externalEvent.isStaleRate(),
                externalEvent.occurredAt()
            );

            // Outbox 테이블에 저장
            OutboxEvent outboxEvent = new OutboxEvent("Ledger", externalEvent.accountId().toString(),
                    "LedgerRecordingCommand", jsonMapper.writeValueAsString(payload), correlationId);
            outboxRepository.save(outboxEvent);
        } catch (Exception e) {
            log.error("Failed to translate/serialize TradeExecutedEvent to Outbox", e);
            throw new com.github.raonjena99.multi_currency_ledger_service.common.exception.EventPublishingException(
                    "Outbox serialization error", e);
        }
    }
}
