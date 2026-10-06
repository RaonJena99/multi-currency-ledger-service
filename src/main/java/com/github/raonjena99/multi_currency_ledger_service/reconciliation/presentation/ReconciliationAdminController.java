package com.github.raonjena99.multi_currency_ledger_service.reconciliation.presentation;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;

import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import com.github.raonjena99.multi_currency_ledger_service.common.domain.Money;
import com.github.raonjena99.multi_currency_ledger_service.common.model.AssetType;
import com.github.raonjena99.multi_currency_ledger_service.common.model.FailureReason;
import com.github.raonjena99.multi_currency_ledger_service.reconciliation.ReconciliationDeadLetterRepository;
import com.github.raonjena99.multi_currency_ledger_service.reconciliation.application.ingestion.SettlementCsvIngestionService;
import com.github.raonjena99.multi_currency_ledger_service.reconciliation.application.ingestion.SettlementCsvIngestionService.IngestionResult;
import com.github.raonjena99.multi_currency_ledger_service.reconciliation.application.service.ManualReconciliationService;
import com.github.raonjena99.multi_currency_ledger_service.reconciliation.domain.ExternalSettlement;
import com.github.raonjena99.multi_currency_ledger_service.reconciliation.domain.ReconciliationDeadLetter;
import com.github.raonjena99.multi_currency_ledger_service.reconciliation.infrastructure.ExternalSettlementRepository;
import com.github.raonjena99.multi_currency_ledger_service.reconciliation.infrastructure.scheduler.ReconciliationJobScheduler;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;

/**
 * 백오피스 관리자(Admin)가 대사 실패 건(Dead Letter)을 수동으로 처리하기 위한 REST API 컨트롤러(Controller)입니다.
 */
@Tag(name = "대사 (관리자)", description = "PG 정산 내역 적재, 대사 배치 실행, 실패 건 처리")
@RestController
@RequestMapping("/api/v1/admin/reconciliations")
@RequiredArgsConstructor
public class ReconciliationAdminController {

    private final ManualReconciliationService manualReconciliationService;
    private final SettlementCsvIngestionService csvIngestionService;
    private final ReconciliationJobScheduler jobScheduler;
    private final ReconciliationDeadLetterRepository deadLetterRepository;
    private final ExternalSettlementRepository settlementRepository;

    /**
     * 대사 배치 실행 결과입니다.
     *
     * @param matched      내부 거래와 매칭된 정산 수
     * @param deadLettered 매칭하지 못해 대사 데드레터로 격리된 정산 수
     */
    public record JobRunResponse(String month, BatchStatus status, String exitCode, long read, long matched,
                                 long deadLettered) {}

    public record DeadLetterSummary(Long id, UUID externalSettlementId, String externalReferenceId,
                                    FailureReason failureReason, String errorMessage, OffsetDateTime createdAt) {}

    public record DeadLetterListResponse(long totalCount, List<DeadLetterSummary> deadLetters) {}

    /**
     * PG 가 내려준 정산 내역 CSV 를 적재합니다. 형식은 {@link SettlementCsvIngestionService} 를 참고하십시오.
     */
    @Operation(summary = "PG 정산 내역 CSV 업로드",
            description = "머리글: transactionId,currency,amount,fee,status,settledAt. 잘못된 행은 행 번호와 사유를 돌려주고 나머지는 적재합니다.")
    @ApiResponse(responseCode = "200", description = "성공 (행별 결과 포함)")
    @ApiResponse(responseCode = "400", description = "빈 파일, 머리글 형식 오류, 행 수 상한 초과 (한 건도 적재하지 않음)")
    @PostMapping(value = "/settlements/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<IngestionResult> uploadSettlements(@RequestParam("file") MultipartFile file)
            throws IOException {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("빈 파일입니다.");
        }
        try (InputStream in = file.getInputStream()) {
            return ResponseEntity.ok(csvIngestionService.ingest(in));
        }
    }

    /**
     * 지정한 월의 대사 배치를 바로 실행하고, 끝날 때까지 기다려 결과를 돌려줍니다.
     * 매월 1일 스케줄 실행을 기다리지 않고 적재 직후 확인하는 용도입니다.
     */
    @Operation(summary = "대사 배치 즉시 실행 (끝날 때까지 기다림)")
    @ApiResponse(responseCode = "200", description = "성공")
    @PostMapping("/jobs")
    public ResponseEntity<JobRunResponse> runJob(@RequestParam("month") String month) throws Exception {
        YearMonth target;
        try {
            target = YearMonth.parse(month);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("month 는 yyyy-MM 형식이어야 합니다: " + month);
        }
        JobExecution execution = jobScheduler.run(target.atDay(1).atStartOfDay().atOffset(ZoneOffset.UTC));

        long read = execution.getStepExecutions().stream().mapToLong(StepExecution::getReadCount).sum();
        long matched = execution.getStepExecutions().stream().mapToLong(StepExecution::getWriteCount).sum();
        long deadLettered = execution.getStepExecutions().stream().mapToLong(StepExecution::getSkipCount).sum();
        return ResponseEntity.ok(new JobRunResponse(target.toString(), execution.getStatus(),
                execution.getExitStatus().getExitCode(), read, matched, deadLettered));
    }

    /**
     * 아직 처리하지 않은 대사 데드레터를 조회합니다. 수동 매칭(resolve) 전에 대상을 확인하는 용도입니다.
     */
    @Operation(summary = "미해결 대사 데드레터 목록")
    @GetMapping("/dead-letters")
    public ResponseEntity<DeadLetterListResponse> listDeadLetters(
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "50") int size) {
        Page<ReconciliationDeadLetter> result = deadLetterRepository.findUnresolvedDeadLetters(
                PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, 500)));
        List<DeadLetterSummary> summaries = result.stream().map(deadLetter -> new DeadLetterSummary(
                deadLetter.getId(),
                deadLetter.getExternalSettlementId(),
                settlementRepository.findByIdWithoutPartitionKey(deadLetter.getExternalSettlementId())
                        .map(ExternalSettlement::getExternalReferenceId).orElse(null),
                deadLetter.getFailureReason(),
                deadLetter.getErrorMessage(),
                deadLetter.getCreatedAt())).toList();
        return ResponseEntity.ok(new DeadLetterListResponse(result.getTotalElements(), summaries));
    }

    /**
     * 수동 매칭 요청 데이터를 담는 DTO(Data Transfer Object) 레코드입니다.
     */
    public record ManualResolutionRequest(
            @NotNull UUID internalTransactionId,
            // Money 생성 시 자산 단위로 반올림(setScale)하므로, 1e999999999 같은 극단적인 지수는
            // 거대한 정수를 만들며 스레드를 멈추게 한다. 거래 API 와 같은 자릿수 상한을 둔다.
            @Digits(integer = 18, fraction = 100) BigDecimal feeAmount,
            AssetType feeAssetType,
            String feeCurrency
    ) {
        /**
         * 입력받은 원시 타입 데이터를 도메인 객체(Money)로 안전하게 변환하는 편의 메서드입니다.
         *
         * <p>금액만 있고 자산 유형/통화가 빠진 <b>부분 입력</b>은 조용히 무시(null 반환)하지 않고
         * 명시적으로 거부합니다. 조용히 무시하면 관리자는 보정이 접수된 줄 알지만(HTTP 200)
         * 실제로는 아무 분개도 만들어지지 않습니다.
         *
         * @return 변환된 수수료 차액 객체 (Money), 수수료 입력이 전혀 없을 경우 null
         * @throws IllegalArgumentException 수수료 입력이 불완전한 경우
         */
        public Money getFeeDifference() {
            boolean amountPresent = feeAmount != null;
            boolean typePresent = feeAssetType != null;
            boolean currencyPresent = feeCurrency != null && !feeCurrency.isBlank();

            if (!amountPresent && !typePresent && !currencyPresent) {
                return null;
            }
            if (!amountPresent || !typePresent || !currencyPresent) {
                throw new IllegalArgumentException(
                        "수수료 보정에는 feeAmount, feeAssetType, feeCurrency 가 모두 필요합니다.");
            }
            // 세 필드가 모두 있는 명시적 0원은 "보정 없음"으로 해석한다.
            if (feeAmount.compareTo(BigDecimal.ZERO) == 0) {
                return null;
            }
            return Money.of(feeAmount, feeAssetType, feeCurrency);
        }
    }

    /**
     * 관리자가 데드 레터 건을 특정 내부 트랜잭션과 강제로 매핑하고, 필요 시 보정 수수료를 함께 처리합니다.
     * 
     * @param deadLetterId 처리할 데드 레터의 ID
     * @param request 수동 매칭 및 수수료 보정 정보가 담긴 요청 객체
     * @return 처리 성공 상태 (ResponseEntity<Void>)
     */
    @Operation(summary = "대사 데드레터 수동 매칭")
    @ApiResponse(responseCode = "200", description = "성공")
    @ApiResponse(responseCode = "422", description = "정산 상태가 매칭할 수 없는 상태")
    @PostMapping("/dead-letters/{deadLetterId}/resolve")
    public ResponseEntity<Void> resolveDeadLetter(
            @PathVariable Long deadLetterId,
            @Valid @RequestBody ManualResolutionRequest request) {
        
        manualReconciliationService.resolveManually(
                deadLetterId, 
                request.internalTransactionId(), 
                request.getFeeDifference()
        );
        
        return ResponseEntity.ok().build();
    }
}
