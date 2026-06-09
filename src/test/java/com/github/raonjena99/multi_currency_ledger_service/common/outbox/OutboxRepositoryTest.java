package com.github.raonjena99.multi_currency_ledger_service.common.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.github.raonjena99.multi_currency_ledger_service.IntegrationTestSupport;

@DisplayName("영속성 통합 테스트: OutboxRepository (트랜잭셔널 아웃박스 패턴 검증)")
class OutboxRepositoryTest extends IntegrationTestSupport {

    @Autowired
    private OutboxRepository outboxRepository;

    @Test
    @DisplayName("도메인 이벤트가 Outbox 테이블에 안전하게 저장되고 조회되어야 한다.")
    void save_and_find_pending_outbox_events() {
        // given
        String aggregateId = UUID.randomUUID().toString();

        OutboxEvent pendingEvent = null;

        // 생성자를 사용하되, 파라미터가 4개 이상인 경우
        pendingEvent = new OutboxEvent("Account", aggregateId, "TradeExecutedEvent", "{\"tradeId\":\"" + aggregateId + "\"}");
        
        // when
        outboxRepository.saveAndFlush(pendingEvent);
        List<OutboxEvent> allEvents = outboxRepository.findAll();
        
        // then
        assertThat(allEvents).isNotEmpty();
        OutboxEvent savedEvent = allEvents.stream()
            .filter(e -> aggregateId.equals(e.getAggregateId())) // Getter 명칭이 다를 경우 수정 필요 (예: e.getId())
            .findFirst()
            .orElseThrow(() -> new AssertionError("Outbox DB에 이벤트가 저장되지 않았습니다."));

        assertThat(savedEvent.getEventType()).isEqualTo("TradeExecutedEvent");
        assertThat(savedEvent.getPayload()).contains("tradeId");
    }
}