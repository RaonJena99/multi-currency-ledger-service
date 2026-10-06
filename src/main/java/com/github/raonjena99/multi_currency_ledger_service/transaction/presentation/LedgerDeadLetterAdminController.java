package com.github.raonjena99.multi_currency_ledger_service.transaction.presentation;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.github.raonjena99.multi_currency_ledger_service.transaction.application.LedgerDeadLetterRecoveryService;
import com.github.raonjena99.multi_currency_ledger_service.transaction.domain.LedgerDeadLetter;

import lombok.RequiredArgsConstructor;

/**
 * 백오피스 관리자(Admin)가 원장 데드레터를 조회하고 복구하기 위한 REST API 컨트롤러입니다.
 *
 * <p>원장 데드레터는 잔고와 분개가 어긋난 상태를 뜻합니다. 이 경로가 없으면 운영자가 DB 에 직접
 * 접속하지 않는 한 어긋난 원장을 되돌릴 방법이 없습니다.
 */
@RestController
@RequestMapping("/api/v1/admin/ledger/dead-letters")
@RequiredArgsConstructor
public class LedgerDeadLetterAdminController {

    private final LedgerDeadLetterRecoveryService recoveryService;

    public record DeadLetterSummary(
            Long id,
            String originalTopic,
            String errorMessage,
            String correlationId,
            String payload,
            OffsetDateTime createdAt
    ) {
        static DeadLetterSummary from(LedgerDeadLetter deadLetter) {
            return new DeadLetterSummary(
                    deadLetter.getId(),
                    deadLetter.getOriginalTopic(),
                    deadLetter.getErrorMessage(),
                    deadLetter.getCorrelationId(),
                    deadLetter.getPayload(),
                    deadLetter.getCreatedAt());
        }
    }

    public record DeadLetterListResponse(long totalCount, List<DeadLetterSummary> deadLetters) {}

    public record ReplayResponse(UUID transactionId) {}

    /**
     * 미해결 데드레터를 최신순으로 조회합니다.
     */
    @GetMapping
    public ResponseEntity<DeadLetterListResponse> listDeadLetters(
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "50") int size) {
        Page<LedgerDeadLetter> result = recoveryService.findUnresolved(Math.max(page, 0), Math.clamp(size, 1, 500));
        List<DeadLetterSummary> summaries = result.stream().map(DeadLetterSummary::from).toList();
        return ResponseEntity.ok(new DeadLetterListResponse(result.getTotalElements(), summaries));
    }

    /**
     * 저장된 페이로드로 원장 기록을 다시 실행하고, 성공하면 해결 처리합니다.
     */
    @PostMapping("/{deadLetterId}/replay")
    public ResponseEntity<ReplayResponse> replayDeadLetter(@PathVariable Long deadLetterId) {
        return ResponseEntity.ok(new ReplayResponse(recoveryService.replay(deadLetterId)));
    }

    /**
     * 재처리 없이 해결 처리만 합니다. 운영자가 원장을 직접 보상한 경우에 사용합니다.
     */
    @PostMapping("/{deadLetterId}/resolve")
    public ResponseEntity<Void> resolveDeadLetter(@PathVariable Long deadLetterId) {
        recoveryService.resolve(deadLetterId);
        return ResponseEntity.ok().build();
    }
}
