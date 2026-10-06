package com.github.raonjena99.multi_currency_ledger_service.transaction.application;

import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.github.raonjena99.multi_currency_ledger_service.common.exception.LedgerDeadLetterNotFoundException;
import com.github.raonjena99.multi_currency_ledger_service.common.exception.LedgerReplayFailedException;
import com.github.raonjena99.multi_currency_ledger_service.transaction.application.command.LedgerRecordingCommand;
import com.github.raonjena99.multi_currency_ledger_service.transaction.domain.LedgerDeadLetter;
import com.github.raonjena99.multi_currency_ledger_service.transaction.infrastructure.LedgerDeadLetterRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.json.JsonMapper;

/**
 * DLT 로 격리된 원장 기록 실패 건을 운영자가 복구하기 위한 서비스입니다.
 *
 * <p>재처리는 Kafka 컨슈머({@code OrderToLedgerAcl})와 같은 경로로 {@link LedgerService#recordDoubleEntry}
 * 를 다시 실행합니다. 그 안의 거래 ID 멱등성 검사 덕분에, 이미 분개된 건을 재처리해도 다시 기록되지
 * 않고 해결 처리만 됩니다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LedgerDeadLetterRecoveryService {

    private final LedgerDeadLetterRepository deadLetterRepository;
    private final LedgerService ledgerService;
    private final JsonMapper jsonMapper;

    /**
     * 미해결 데드레터를 최신순으로 조회합니다.
     */
    @Transactional(readOnly = true)
    public Page<LedgerDeadLetter> findUnresolved(int page, int size) {
        return deadLetterRepository.findUnresolved(PageRequest.of(page, size));
    }

    /**
     * 저장된 페이로드로 원장 기록을 다시 실행하고, 성공하면 해결 처리합니다.
     *
     * <p>원장 기록은 {@code REQUIRES_NEW} 로 먼저 커밋되고, 해결 처리는 이 트랜잭션이 커밋될 때
     * 버전 검사와 함께 반영됩니다. 다른 운영자가 먼저 처리했다면 여기서 충돌이 나지만,
     * 원장 쪽은 거래 ID 멱등성 검사로 중복 기록되지 않습니다.
     *
     * @return 기록된(또는 이미 기록되어 있던) 거래 ID
     * @throws LedgerDeadLetterNotFoundException 해당 ID 의 데드레터가 없는 경우
     * @throws IllegalStateException             이미 해결된 경우
     * @throws LedgerReplayFailedException       페이로드를 읽지 못했거나 원장 기록이 실패한 경우.
     *                                           데드레터는 미해결 상태로 남습니다.
     */
    @Transactional
    public UUID replay(Long deadLetterId) {
        LedgerDeadLetter deadLetter = findById(deadLetterId);
        if (deadLetter.isResolved()) {
            throw new IllegalStateException("This ledger dead letter has already been resolved.");
        }

        LedgerRecordingCommand command;
        try {
            command = jsonMapper.readValue(deadLetter.getPayload(), LedgerRecordingCommand.class);
            ledgerService.recordDoubleEntry(command);
        } catch (RuntimeException e) {
            log.warn("원장 데드레터 재처리 실패. deadLetterId={}, cause={}", deadLetterId, e.toString());
            throw new LedgerReplayFailedException(deadLetterId, e);
        }

        deadLetter.markAsResolved();
        log.info("원장 데드레터 재처리 완료. deadLetterId={}, transactionId={}", deadLetterId, command.referenceTradeId());
        return command.referenceTradeId();
    }

    /**
     * 재처리 없이 해결 처리만 합니다. 운영자가 원장을 직접 보상한 경우에 사용합니다.
     *
     * @throws LedgerDeadLetterNotFoundException 해당 ID 의 데드레터가 없는 경우
     * @throws IllegalStateException             이미 해결된 경우
     */
    @Transactional
    public void resolve(Long deadLetterId) {
        findById(deadLetterId).markAsResolved();
        log.info("원장 데드레터 수동 해결 처리. deadLetterId={}", deadLetterId);
    }

    private LedgerDeadLetter findById(Long deadLetterId) {
        return deadLetterRepository.findById(deadLetterId)
                .orElseThrow(() -> new LedgerDeadLetterNotFoundException(deadLetterId));
    }
}
