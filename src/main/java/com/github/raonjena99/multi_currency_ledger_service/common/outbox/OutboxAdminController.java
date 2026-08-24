package com.github.raonjena99.multi_currency_ledger_service.common.outbox;

import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

/**
 * 백오피스 관리자(Admin)가 아웃박스 데드레터를 조회하고 재발행하기 위한 REST API 컨트롤러입니다.
 *
 * <p>데드레터는 릴레이 폴링 대상에서 영구 제외되므로, 이 복구 경로가 없으면 브로커 장애 등으로
 * 격리된 원장 이벤트를 되살릴 방법이 없어 at-least-once 전달 보장이 깨집니다.
 */
@RestController
@RequestMapping("/api/v1/admin/outbox")
@RequiredArgsConstructor
public class OutboxAdminController {

    private final OutboxManager outboxManager;
    private final OutboxRepository outboxRepository;

    public record DeadLetterSummary(
            Long id,
            String aggregateType,
            String aggregateId,
            String eventType,
            int retryCount,
            String errorMessage,
            OffsetDateTime createdAt
    ) {
        static DeadLetterSummary from(OutboxEvent event) {
            return new DeadLetterSummary(
                    event.getId(),
                    event.getAggregateType(),
                    event.getAggregateId(),
                    event.getEventType(),
                    event.getRetryCount(),
                    event.getErrorMessage(),
                    event.getCreatedAt());
        }
    }

    public record DeadLetterListResponse(long totalCount, List<DeadLetterSummary> deadLetters) {}

    public record RequeueAllResponse(int requeuedCount) {}

    /**
     * 데드레터로 격리된 아웃박스 이벤트를 오래된 순으로 조회합니다.
     */
    @GetMapping("/dead-letters")
    public ResponseEntity<DeadLetterListResponse> listDeadLetters(
            @RequestParam(name = "limit", defaultValue = "100") int limit) {
        int boundedLimit = Math.clamp(limit, 1, 1000);
        List<DeadLetterSummary> summaries = outboxRepository
                .findByDeadLetterTrueOrderByCreatedAtAsc(PageRequest.of(0, boundedLimit)).stream()
                .map(DeadLetterSummary::from)
                .toList();
        return ResponseEntity.ok(new DeadLetterListResponse(outboxRepository.countByDeadLetterTrue(), summaries));
    }

    /**
     * 데드레터 하나를 재발행 대상으로 되돌립니다. 다음 릴레이 주기에 다시 발행됩니다.
     */
    @PostMapping("/dead-letters/{eventId}/requeue")
    public ResponseEntity<Void> requeueDeadLetter(@PathVariable Long eventId) {
        outboxManager.requeueDeadLetter(eventId);
        return ResponseEntity.ok().build();
    }

    /**
     * 데드레터 전체(최대 {@code limit} 건)를 재발행 대상으로 되돌립니다.
     * 브로커 장애가 복구된 뒤 일괄 재발행하는 용도입니다.
     */
    @PostMapping("/dead-letters/requeue-all")
    public ResponseEntity<RequeueAllResponse> requeueAllDeadLetters(
            @RequestParam(name = "limit", defaultValue = "1000") int limit) {
        int boundedLimit = Math.clamp(limit, 1, 10000);
        return ResponseEntity.ok(new RequeueAllResponse(outboxManager.requeueAllDeadLetters(boundedLimit)));
    }
}
