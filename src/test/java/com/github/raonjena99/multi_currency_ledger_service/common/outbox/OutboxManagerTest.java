package com.github.raonjena99.multi_currency_ledger_service.common.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OutboxManagerTest {

    @Mock
    private OutboxRepository outboxRepository;

    @InjectMocks
    private OutboxManager outboxManager;

    @Test
    void claimUnprocessedEvents_should_lock_and_return_events() {
        OutboxEvent event = new OutboxEvent("agg", "1", "event", "{}", "corr");
        assertThat(event.getLockedAt()).isNull();

        when(outboxRepository.findUnprocessedEventsWithSkipLocked(anyInt(), any(), any())).thenReturn(Arrays.asList(event));

        List<OutboxEvent> result = outboxManager.claimUnprocessedEvents(10);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getLockedAt()).isNotNull();
    }

    @Test
    void updateResults_should_process_success_and_failures() {
        OutboxEvent failedEvent = new OutboxEvent("agg", "1", "event", "{}", "corr");

        outboxManager.updateResults(Arrays.asList(1L, 2L), Arrays.asList(failedEvent));

        verify(outboxRepository).markAsProcessedInBatch(Arrays.asList(1L, 2L));
        verify(outboxRepository).saveAll(Arrays.asList(failedEvent));
    }

    @Test
    void updateResults_should_skip_empty_lists() {
        outboxManager.updateResults(Collections.emptyList(), Collections.emptyList());

        org.mockito.Mockito.verifyNoInteractions(outboxRepository);
    }
}
