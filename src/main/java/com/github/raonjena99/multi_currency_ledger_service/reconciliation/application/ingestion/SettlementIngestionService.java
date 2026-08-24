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

    private final SettlementRecorder settlementRecorder;

    /**
     * 외부 거래 ID 목록을 조회해 정산 내역으로 적재합니다.
     *
     * <p>이 메서드 자체는 트랜잭션을 열지 않습니다. 한 건의 실패가 이미 적재된 건을
     * 되돌리지 않도록 트랜잭션 경계를 건별로 유지하는 것이 목적입니다.
     *
     * @param externalTransactionIds 적재할 외부 거래 ID 목록
     * @return 새로 적재된 건수
     */
    public int ingest(List<String> externalTransactionIds) {
        int ingested = 0;
        for (String transactionId : externalTransactionIds) {
            try {
                // 프록시를 거쳐야 REQUIRES_NEW 가 적용된다.
                if (settlementRecorder.record(transactionId)) {
                    ingested++;
                }
            } catch (Exception e) {
                // 한 건의 실패가 전체 적재를 멈추지 않도록 격리한다.
                log.warn("정산 적재 실패. 다음 건으로 진행합니다. transactionId={}, cause={}",
                        transactionId, e.getMessage());
            }
        }
        log.info("정산 데이터 적재 완료. 요청 {}건 중 {}건 신규 적재.", externalTransactionIds.size(), ingested);
        return ingested;
    }
}
