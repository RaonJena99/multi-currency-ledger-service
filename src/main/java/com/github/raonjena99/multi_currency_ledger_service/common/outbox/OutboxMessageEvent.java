package com.github.raonjena99.multi_currency_ledger_service.common.outbox;

/**
 * Spring 내부 이벤트 버스를 통해 발행되는 OutboxMessageEvent(아웃박스 메시지 이벤트) 레코드입니다.
 *
 * @param eventType 목적지 토픽 또는 이벤트 유형
 * @param payload   발행할 실제 메시지 내용
 * @param correlationId 상호 연관성 있는 이벤트 식별자
 */
public record OutboxMessageEvent(String eventType, String payload, String correlationId) {}
