package com.github.raonjena99.multi_currency_ledger_service.transaction.application;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.github.raonjena99.multi_currency_ledger_service.transaction.infrastructure.query.LedgerIntegrityDao;
import com.github.raonjena99.multi_currency_ledger_service.transaction.infrastructure.query.LedgerIntegrityDao.DifferenceView;
import com.github.raonjena99.multi_currency_ledger_service.transaction.infrastructure.query.LedgerIntegrityDao.MismatchView;
import com.github.raonjena99.multi_currency_ledger_service.transaction.infrastructure.query.LedgerIntegrityDao.RunView;
import com.github.raonjena99.multi_currency_ledger_service.transaction.infrastructure.query.LedgerQueryDao;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;

/**
 * 고객 잔고(월차 원장)와 분개 누계가 맞는지 점검합니다.
 *
 * <p>매수·매도·입출금은 잔고가 먼저 바뀌고 분개는 Kafka 를 거쳐 나중에 기록됩니다. 그래서 분개가
 * DLT 로 빠지거나 아웃박스 이벤트가 데드레터가 되면 둘이 어긋난 채로 남습니다. 이 점검이 그 상태를
 * 먼저 찾아내고, 복구는 원장 DLT 재처리나 아웃박스 재발행으로 합니다.
 *
 * <p><b>오탐 방지.</b> 처리 중인 거래가 있는 동안에는 잔고와 분개가 잠시 다릅니다. 잔고는 최근 거래까지
 * 반영된 값이라 거래 단위로 빼낼 수 없으므로, 아직 정리되지 않은 (계좌, 자산) 은 이번 회차에서 건너뛰고
 * 다음 회차에 다시 봅니다. 아웃박스가 데드레터로 격리된 경우는 분개가 오지 않으므로 건너뛰지 않습니다.
 */
@Slf4j
@Service
public class LedgerIntegrityCheckService {

    private static final OffsetDateTime BEGINNING = OffsetDateTime.of(1970, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);

    private final LedgerIntegrityDao integrityDao;
    private final LedgerQueryDao queryDao;
    private final Duration settleTime;
    private final Duration resultRetention;
    private final AtomicLong lastMismatchCount = new AtomicLong();

    public record Mismatch(UUID accountId, String assetCode, BigDecimal ledgerBalance, BigDecimal journalBalance,
                           BigDecimal difference, boolean outboxDeadLetter) {}

    public record IntegrityCheckResult(UUID runId, OffsetDateTime checkedAt, int checkedCount, int skippedCount,
                                       boolean trialBalanceBalanced, List<Mismatch> mismatches) {}

    public LedgerIntegrityCheckService(LedgerIntegrityDao integrityDao, LedgerQueryDao queryDao,
                                       MeterRegistry meterRegistry,
                                       @Value("${ledger.integrity.settle-minutes:10}") long settleMinutes,
                                       @Value("${ledger.integrity.retention-days:90}") long retentionDays) {
        this.integrityDao = integrityDao;
        this.queryDao = queryDao;
        this.settleTime = Duration.ofMinutes(settleMinutes);
        this.resultRetention = Duration.ofDays(retentionDays);
        // Counter 는 해소돼도 줄지 않아 알림 조건으로 쓸 수 없다. 직전 회차의 건수를 그대로 보여준다.
        Gauge.builder("ledger.integrity.mismatches", lastMismatchCount, AtomicLong::get)
                .description("직전 정합성 점검에서 잔고와 분개 누계가 어긋난 (계좌, 자산) 수. 0 이 아니면 복구가 필요합니다.")
                .register(meterRegistry);
        restoreLastMismatchCount();
    }

    /**
     * 재시작한 인스턴스도 직전 회차의 건수로 시작한다. 0 으로 시작하면 다음 점검(최대 하루 뒤)까지
     * 남아 있는 불일치가 알림에서 사라진다.
     */
    private void restoreLastMismatchCount() {
        try {
            integrityDao.findLatestRun().ifPresent(run -> lastMismatchCount.set(run.mismatchCount()));
        } catch (Exception e) {
            log.warn("직전 원장 정합성 점검 결과를 읽지 못했습니다. 다음 점검까지 지표가 0 으로 보입니다: {}", e.getMessage());
        }
    }

    /**
     * 점검을 실행하고 결과를 저장합니다.
     */
    @Transactional
    public IntegrityCheckResult runCheck() {
        OffsetDateTime checkedAt = OffsetDateTime.now();
        OffsetDateTime settledBefore = checkedAt.minus(settleTime);

        int checkedCount = integrityDao.countPairs();
        int skippedCount = 0;
        List<Mismatch> mismatches = new ArrayList<>();

        for (DifferenceView diff : integrityDao.findDifferences()) {
            boolean recentlyChanged = diff.updatedAt() != null && diff.updatedAt().isAfter(settledBefore);
            if (recentlyChanged || diff.outboxPending()) {
                skippedCount++;
                continue;
            }
            mismatches.add(new Mismatch(diff.accountId(), diff.assetCode(), diff.ledgerBalance(),
                    diff.journalBalance(), diff.ledgerBalance().subtract(diff.journalBalance()),
                    diff.outboxDeadLetter()));
        }

        boolean trialBalanceBalanced = isTrialBalanceBalanced(checkedAt);

        UUID runId = UUID.randomUUID();
        integrityDao.saveRun(
                new RunView(runId, checkedAt, checkedCount, skippedCount, mismatches.size(), trialBalanceBalanced),
                mismatches.stream().map(m -> new MismatchView(m.accountId(), m.assetCode(), m.ledgerBalance(),
                        m.journalBalance(), m.difference(), m.outboxDeadLetter())).toList());
        lastMismatchCount.set(mismatches.size());

        if (!mismatches.isEmpty() || !trialBalanceBalanced) {
            log.error("[CRITICAL] 원장 정합성 점검에서 불일치가 발견되었습니다. runId={}, mismatches={}, trialBalanceBalanced={}",
                    runId, mismatches.size(), trialBalanceBalanced);
        } else {
            log.info("원장 정합성 점검 완료. runId={}, checked={}, skipped={}", runId, checkedCount, skippedCount);
        }
        return new IntegrityCheckResult(runId, checkedAt, checkedCount, skippedCount, trialBalanceBalanced, mismatches);
    }

    /**
     * 보존 기간이 지난 점검 결과를 불일치 기록과 함께 지웁니다.
     *
     * @return 지운 회차 수
     */
    @Transactional
    public int purgeOldRuns() {
        int purged = integrityDao.deleteRunsCheckedBefore(OffsetDateTime.now().minus(resultRetention));
        if (purged > 0) {
            log.info("보존 기간({})이 지난 원장 정합성 점검 결과 {}회차를 삭제했습니다.", resultRetention, purged);
        }
        return purged;
    }

    /**
     * 가장 최근 점검 결과를 조회합니다.
     */
    @Transactional(readOnly = true)
    public Optional<IntegrityCheckResult> findLatest() {
        return integrityDao.findLatestRun().map(run -> new IntegrityCheckResult(
                run.runId(), run.checkedAt(), run.checkedCount(), run.skippedCount(), run.trialBalanceBalanced(),
                integrityDao.findMismatches(run.runId()).stream()
                        .map(m -> new Mismatch(m.accountId(), m.assetCode(), m.ledgerBalance(), m.journalBalance(),
                                m.difference(), m.outboxDeadLetter()))
                        .toList()));
    }

    /**
     * 지금까지의 모든 분개가 기준 통화별로 {@code 차변 = 대변} 을 만족하는지 확인합니다.
     * 실현 손익은 고객 계정의 별도 분개라 합계에 이미 들어 있습니다.
     */
    private boolean isTrialBalanceBalanced(OffsetDateTime now) {
        return queryDao.sumByCurrency(BEGINNING, now.plusDays(1)).stream()
                .allMatch(t -> t.debitTotal().compareTo(t.creditTotal()) == 0);
    }
}
