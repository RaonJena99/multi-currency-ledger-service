package com.github.raonjena99.multi_currency_ledger_service.common.outbox;

import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.github.raonjena99.multi_currency_ledger_service.transaction.application.LedgerService;
import com.github.raonjena99.multi_currency_ledger_service.transaction.application.command.LedgerRecordingCommand;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.json.JsonMapper;

@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxRelayWorker {

    private final OutboxRepository outboxRepository;
    private final LedgerService ledgerService;
    private final JsonMapper jsonMapper;

    @Scheduled(fixedDelay = 5000)
    @Transactional
    public void relayOutboxEvents() {
        List<OutboxEvent> events = outboxRepository.findUnprocessedEvents(PageRequest.of(0, 100));

        for (OutboxEvent event : events) {
            try {
                if ("LedgerRecordingCommand".equals(event.getEventType())) {
                    LedgerRecordingCommand command = jsonMapper.readValue(event.getPayload(), LedgerRecordingCommand.class);
                    ledgerService.recordDoubleEntry(command);
                }
                event.markAsProcessed();
            } catch (Exception e) {
                log.error("Failed to process OutboxEvent ID: {}. Triggering Failure Logic.", event.getId(), e);
                event.recordFailure(e.getMessage());
            }
        }
    }
}
