package com.github.raonjena99.multi_currency_ledger_service.reconciliation.application.ingestion;

import java.util.List;

import org.springframework.stereotype.Service;

import com.github.raonjena99.multi_currency_ledger_service.reconciliation.domain.ExternalSettlement;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 외부 PG 정산 데이터를 내부 {@link ExternalSettlement} 로 적재합니다.
 *
 * <p>이 적재 경로가 없으면 대사 배치는 읽을 데이터가 없어 아무것도 하지 않습니다.
 * 룰 엔진과 배치 잡 정의만 있고 입력이 없던 상태를 이 서비스가 메웁니다.
 *
 * <p>건별 트랜잭션은 {@link SettlementRecorder} 가 담당합니다. 이 클래스에 함께 두면
 * 내부 호출이 프록시를 거치지 않아 {@code REQUIRES_NEW} 가 무시되고 건별 격리가 사라집니다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SettlementIngestionService {

    /**
     * 적재를 실패로 판정하는 실패율 임계값. 이 비율을 넘으면 부분 장애가 아니라
     * PG 연동 자체의 장애로 간주하고 예외를 올려 잡을 실패시킵니다.
     */
    private static final double FAILURE_RATE_THRESHOLD = 0.10;

    /** 실패율 판정을 적용하기 위한 최소 실패 건수. 소량 입력에서의 과민 반응을 막습니다. */
    private static final int MIN_FAILURES_FOR_ABORT = 10;

    private final SettlementRecorder settlementRecorder;

    /**
     * 외부 거래 ID 목록을 조회해 정산 내역으로 적재합니다.
     *
     * <p>이 메서드 자체는 트랜잭션을 열지 않습니다. 한 건의 실패가 이미 적재된 건을
     * 되돌리지 않도록 트랜잭션 경계를 건별로 유지하는 것이 목적입니다.
     *
     * <p><b>실패를 무제한 삼키지 않습니다.</b> 건별 격리는 산발적 실패를 위한 것이지,
     * PG API 전면 장애까지 "성공"으로 보고하기 위한 것이 아닙니다. 실패가 임계값을 넘으면
     * 예외를 올려 스케줄러/잡이 실패로 기록되게 합니다. 그렇지 않으면 3만 건이 전부 실패해도
     * WARN 로그만 남고, 그 달의 대사는 빈 입력에 대해 "완료"됩니다.
     *
     * @param externalTransactionIds 적재할 외부 거래 ID 목록
     * @return 새로 적재된 건수
     * @throws SettlementIngestionDegradedException 실패율이 임계값을 초과한 경우
     */
    public int ingest(List<String> externalTransactionIds) {
        int ingested = 0;
        int failed = 0;
        for (String transactionId : externalTransactionIds) {
            try {
                // 프록시를 거쳐야 REQUIRES_NEW 가 적용된다.
                if (settlementRecorder.record(transactionId)) {
                    ingested++;
                }
            } catch (Exception e) {
                // 한 건의 실패가 전체 적재를 멈추지 않도록 격리한다.
                failed++;
                log.warn("정산 적재 실패. 다음 건으로 진행합니다. transactionId={}, cause={}",
                        transactionId, e.getMessage());
            }
        }

        log.info("정산 데이터 적재 완료. 요청 {}건 중 {}건 신규 적재, {}건 실패.",
                externalTransactionIds.size(), ingested, failed);

        if (failed >= MIN_FAILURES_FOR_ABORT
                && failed > externalTransactionIds.size() * FAILURE_RATE_THRESHOLD) {
            throw new SettlementIngestionDegradedException(String.format(
                    "정산 적재 실패율이 임계값을 초과했습니다. 요청 %d건 중 %d건 실패. PG 연동 장애를 점검하십시오.",
                    externalTransactionIds.size(), failed));
        }
        return ingested;
    }

    /** 적재 실패율이 임계값을 초과했음을 알리는 예외입니다. 잡/스케줄러가 실패로 기록해야 합니다. */
    public static class SettlementIngestionDegradedException extends RuntimeException {
        public SettlementIngestionDegradedException(String message) {
            super(message);
        }
    }
}
