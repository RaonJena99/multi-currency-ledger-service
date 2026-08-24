package com.github.raonjena99.multi_currency_ledger_service.regression;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.github.raonjena99.multi_currency_ledger_service.common.outbox.OutboxEvent;
import com.github.raonjena99.multi_currency_ledger_service.common.outbox.OutboxManager;
import com.github.raonjena99.multi_currency_ledger_service.common.outbox.OutboxMessageDispatcher;
import com.github.raonjena99.multi_currency_ledger_service.common.outbox.OutboxRelayWorker;

/**
 * 발송 중 한 건이 <b>동기 예외</b>를 던져도 배치 전체 결과가 유실되지 않는지 검증합니다.
 *
 * <p>{@code KafkaTemplate.send} 는 버퍼 고갈, 직렬화 실패, 메타데이터 타임아웃에서 동기 예외를
 * 던질 수 있습니다. 발송 준비 루프가 try 블록 밖에 있으면 예외가 finally 를 건너뛰어 이미 성공한
 * 이벤트의 successIds 가 버려지고(중복 발행), 선점된 행들이 실패 기록 없이 잠긴 채 남습니다.
 */
@DisplayName("회귀 테스트: 아웃박스 릴레이 복원력")
class OutboxRelayResilienceTest {

    private OutboxEvent event(long id) {
        OutboxEvent e = mock(OutboxEvent.class);
        when(e.getId()).thenReturn(id);
        return e;
    }

    @Test
    @DisplayName("한 건이 동기 예외를 던져도 나머지 성공 건의 결과가 반영된다")
    void synchronous_dispatch_failure_does_not_lose_batch_results() {
        OutboxManager manager = mock(OutboxManager.class);
        OutboxMessageDispatcher dispatcher = mock(OutboxMessageDispatcher.class);

        OutboxEvent ok1 = event(1L);
        OutboxEvent boom = event(2L);
        OutboxEvent ok2 = event(3L);

        when(manager.claimUnprocessedEvents(100)).thenReturn(List.of(ok1, boom, ok2));

        when(dispatcher.dispatch(ok1)).thenReturn(CompletableFuture.completedFuture(null));
        when(dispatcher.dispatch(ok2)).thenReturn(CompletableFuture.completedFuture(null));
        // 두 번째 건은 Future 를 반환하지 못하고 즉시 예외를 던진다.
        doAnswer(invocation -> { throw new IllegalStateException("producer buffer full"); })
                .when(dispatcher).dispatch(boom);

        OutboxRelayWorker worker = new OutboxRelayWorker(manager, dispatcher);
        worker.relayOutboxEvents();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Long>> successCaptor = ArgumentCaptor.forClass(List.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<OutboxEvent>> failedCaptor = ArgumentCaptor.forClass(List.class);

        org.mockito.Mockito.verify(manager).updateResults(successCaptor.capture(), failedCaptor.capture());

        assertThat(successCaptor.getValue())
                .as("동기 예외가 나도 성공 건의 ID 는 보존되어야 한다")
                .containsExactlyInAnyOrder(1L, 3L);
        assertThat(failedCaptor.getValue())
                .as("실패 건은 실패 목록에 기록되어 재시도 카운트가 올라가야 한다")
                .containsExactly(boom);

        org.mockito.Mockito.verify(boom).recordFailure(any());
        org.mockito.Mockito.verify(boom).unlock();
    }

    @Test
    @DisplayName("비동기 실패도 실패 목록에 기록된다")
    void asynchronous_dispatch_failure_is_recorded() {
        OutboxManager manager = mock(OutboxManager.class);
        OutboxMessageDispatcher dispatcher = mock(OutboxMessageDispatcher.class);

        OutboxEvent failing = event(9L);
        when(manager.claimUnprocessedEvents(100)).thenReturn(List.of(failing));
        when(dispatcher.dispatch(failing))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker down")));

        new OutboxRelayWorker(manager, dispatcher).relayOutboxEvents();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<OutboxEvent>> failedCaptor = ArgumentCaptor.forClass(List.class);
        org.mockito.Mockito.verify(manager).updateResults(any(), failedCaptor.capture());

        assertThat(failedCaptor.getValue()).containsExactly(failing);
    }

    @Test
    @DisplayName("처리할 이벤트가 없으면 업데이트를 호출하지 않는다")
    void no_events_means_no_update() {
        OutboxManager manager = mock(OutboxManager.class);
        OutboxMessageDispatcher dispatcher = mock(OutboxMessageDispatcher.class);
        when(manager.claimUnprocessedEvents(100)).thenReturn(List.of());

        new OutboxRelayWorker(manager, dispatcher).relayOutboxEvents();

        org.mockito.Mockito.verify(manager, org.mockito.Mockito.never()).updateResults(any(), any());
    }
}
