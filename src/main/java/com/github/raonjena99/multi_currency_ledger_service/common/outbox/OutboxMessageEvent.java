package com.github.raonjena99.multi_currency_ledger_service.common.outbox;

public record OutboxMessageEvent(String eventType, String payload) {}
