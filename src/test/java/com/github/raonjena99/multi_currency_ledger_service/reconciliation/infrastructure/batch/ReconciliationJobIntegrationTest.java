package com.github.raonjena99.multi_currency_ledger_service.reconciliation.infrastructure.batch;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.test.JobOperatorTestUtils;
import org.springframework.batch.test.context.SpringBatchTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.github.raonjena99.multi_currency_ledger_service.IntegrationTestSupport;
import com.github.raonjena99.multi_currency_ledger_service.common.domain.Money;
import com.github.raonjena99.multi_currency_ledger_service.common.model.AssetType;
import com.github.raonjena99.multi_currency_ledger_service.common.model.SettlementStatus;
import com.github.raonjena99.multi_currency_ledger_service.common.outbox.OutboxRelayWorker;
import com.github.raonjena99.multi_currency_ledger_service.reconciliation.ReconciliationDeadLetterRepository;
import com.github.raonjena99.multi_currency_ledger_service.reconciliation.domain.ExternalSettlement;
import com.github.raonjena99.multi_currency_ledger_service.reconciliation.domain.ExternalSettlementId;
import com.github.raonjena99.multi_currency_ledger_service.reconciliation.infrastructure.ExternalSettlementRepository;

@SpringBatchTest
class ReconciliationJobIntegrationTest extends IntegrationTestSupport {

    @Autowired private JobOperatorTestUtils jobOperatorTestUtils;
    @Autowired @Qualifier("monthlyReconciliationJob") private Job monthlyReconciliationJob;
    @Autowired private ExternalSettlementRepository settlementRepository;
    @Autowired private ReconciliationDeadLetterRepository deadLetterRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private UUID successSettlementId;
    private OffsetDateTime successSettlementDate; 
    private UUID failSettlementId;
    private OffsetDateTime failSettlementDate;   

    @MockitoBean
    private OutboxRelayWorker outboxRelayWorker;

    @BeforeEach
    void setUp() {
        jobOperatorTestUtils.setJob(monthlyReconciliationJob);

        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS external_settlement_default PARTITION OF external_settlement DEFAULT");

        deadLetterRepository.deleteAllInBatch();
        settlementRepository.deleteAllInBatch();
        jdbcTemplate.update("DELETE FROM outbox_events"); 
        jdbcTemplate.update("DELETE FROM transaction_entries");
        jdbcTemplate.update("DELETE FROM transactions");
        jdbcTemplate.update("DELETE FROM accounts");

        successSettlementDate = OffsetDateTime.parse("2026-06-15T10:00:00Z");
        ExternalSettlement successExt = ExternalSettlement.create(
                "REF_SUCCESS_01", "TOSS", successSettlementDate,
                "TOSS_PAYMENTS", Money.of("1000", AssetType.FIAT)
        );
        successSettlementId = successExt.getId();
        settlementRepository.save(successExt); 

        UUID tId = UUID.randomUUID(); 
        UUID accountId = UUID.randomUUID();
        
        jdbcTemplate.update("INSERT INTO accounts (id, owner_name, status) VALUES (?, 'TEST_USER', 'ACTIVE')", accountId);
        
        jdbcTemplate.update("INSERT INTO transactions (id, transaction_type, transacted_at, description) VALUES (?, 'DEPOSIT', '2026-06-15 10:00:00+00', 'TOSS_PAYMENTS')", tId);
        
        jdbcTemplate.update("INSERT INTO transaction_entries (" +
                "transaction_id, account_id, entry_type, asset_code, " +
                "quantity_asset_type, quantity, " +
                "unit_price, unit_price_asset_type, " +
                "amount, amount_asset_type, " +
                "realized_pnl, realized_pnl_asset_type) " +
                "VALUES (?, ?, 'CREDIT', 'KRW', 'FIAT', 1000, 1, 'FIAT', 1000, 'FIAT', 0, 'FIAT')", 
                tId, accountId);

        failSettlementDate = OffsetDateTime.parse("2026-06-25T10:00:00Z");
        ExternalSettlement failExt = ExternalSettlement.create(
                "REF_FAIL_02", "TOSS", failSettlementDate,
                "GHOST_PAY", Money.of("99999", AssetType.FIAT)
        );
        failSettlementId = failExt.getId();
        settlementRepository.save(failExt);
    }

    @AfterEach
    void tearDown() {
        deadLetterRepository.deleteAllInBatch();
        settlementRepository.deleteAllInBatch();
        jdbcTemplate.update("DELETE FROM outbox_events");
        jdbcTemplate.update("DELETE FROM transaction_entries");
        jdbcTemplate.update("DELETE FROM transactions");
        jdbcTemplate.update("DELETE FROM accounts");
    }

    @Test
    @DisplayName("[Batch E2E] 대사 배치가 실행되면 성공 건은 MATCHED, 실패 건은 DLQ로 완벽히 분리되어야 한다")
    void testMonthlyReconciliationJob_SuccessAndSkipIsolation() throws Exception {
        // 혹시 모를 파라미터 누락 방지를 위해 targetMonth 명시 추가
        JobParameters jobParameters = new JobParametersBuilder()
                .addString("targetMonth", "2026-06")
                .addString("startOfMonth", "2026-06-01T00:00:00Z")
                .addLong("time", System.currentTimeMillis()) 
                .toJobParameters();

        JobExecution jobExecution = jobOperatorTestUtils.startJob(jobParameters);
        
        if (jobExecution.getStatus() != BatchStatus.COMPLETED) {
            jobExecution.getAllFailureExceptions().forEach(Throwable::printStackTrace);
            throw new AssertionError("Job failed! Root Cause: " + jobExecution.getAllFailureExceptions());
        }

        // 성공 건 검증: DB에서 상태가 정확히 MATCHED 로 변했는지 확인
        ExternalSettlementId successCompositeKey = new ExternalSettlementId(successSettlementId, successSettlementDate);
        ExternalSettlement matchedSettlement = settlementRepository.findById(successCompositeKey).orElseThrow();
        
        assertThat(matchedSettlement.getStatus()).isEqualTo(SettlementStatus.MATCHED);
        assertThat(matchedSettlement.getMatchedInternalTransactionId()).isNotNull();

        // 실패 건 검증: MATCHED가 되지 않고 PENDING이나 UNMATCHED로 남았는지 확인
        ExternalSettlementId failCompositeKey = new ExternalSettlementId(failSettlementId, failSettlementDate);
        ExternalSettlement skippedSettlement = settlementRepository.findById(failCompositeKey).orElseThrow();
        
        assertThat(skippedSettlement.getStatus()).isIn(SettlementStatus.UNMATCHED, SettlementStatus.PENDING); 

        // DLQ(데드레터) 검증: 실패 건이 DLQ에 정상적으로 들어갔는지 식별자로만 안전하게 확인
        long dlqCount = deadLetterRepository.count();
        assertThat(dlqCount).isGreaterThanOrEqualTo(1);
        
        boolean isFailInDlq = deadLetterRepository.findAll().stream()
                .anyMatch(dlq -> dlq.getExternalSettlementId().toString().equals(failSettlementId.toString()));
        assertThat(isFailInDlq).as("실패 건이 DLQ에 기록되지 않았습니다.").isTrue();
    }
}