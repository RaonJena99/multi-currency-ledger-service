package com.github.raonjena99.multi_currency_ledger_service.account.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.github.raonjena99.multi_currency_ledger_service.account.infrastructure.IdempotencyRecordRepository;

@ExtendWith(MockitoExtension.class)
class IdempotencyCleanupWorkerTest {

    @Mock
    private IdempotencyRecordRepository repository;

    @InjectMocks
    private IdempotencyCleanupWorker worker;

    @Test
    void cleanupOldRecords_should_loop_until_less_than_1000_deleted() {
        when(repository.deleteByCreatedAtBeforeWithLimit(any(OffsetDateTime.class), anyInt()))
            .thenReturn(1000)
            .thenReturn(500);

        worker.cleanupOldRecords();

        verify(repository, times(2)).deleteByCreatedAtBeforeWithLimit(any(OffsetDateTime.class), anyInt());
    }

    @Test
    void cleanupOldRecords_should_stop_if_zero_deleted() {
        when(repository.deleteByCreatedAtBeforeWithLimit(any(OffsetDateTime.class), anyInt()))
            .thenReturn(0);

        worker.cleanupOldRecords();

        verify(repository, times(1)).deleteByCreatedAtBeforeWithLimit(any(OffsetDateTime.class), anyInt());
    }
}
