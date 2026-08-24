package com.github.raonjena99.multi_currency_ledger_service.reconciliation.application.ingestion;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.github.raonjena99.multi_currency_ledger_service.common.domain.Money;
import com.github.raonjena99.multi_currency_ledger_service.common.model.AssetType;
import com.github.raonjena99.multi_currency_ledger_service.reconciliation.domain.ExternalSettlement;
import com.github.raonjena99.multi_currency_ledger_service.reconciliation.infrastructure.ExternalSettlementRepository;
import com.github.raonjena99.multi_currency_ledger_service.reconciliation.infrastructure.adapter.ExternalSettlementDto;
import com.github.raonjena99.multi_currency_ledger_service.reconciliation.infrastructure.adapter.PgSettlementAdapter;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 외부 정산 <b>단건</b>을 독립 트랜잭션에서 적재합니다.
 *
 * <p>이 클래스가 {@link SettlementIngestionService} 에서 분리되어 있는 것은 의도적입니다.
 * 같은 클래스 안에서 {@code ingestOne(...)} 을 직접 호출하면 Spring 프록시를 거치지 않아
 * {@code @Transactional(propagation = REQUIRES_NEW)} 이 <b>조용히 무시</b>됩니다. 그러면 건별
 * 트랜잭션 격리가 사라져 한 건의 실패가 이미 적재된 건까지 롤백시킬 수 있습니다.
 *
 * <p>반복(루프)과 건별 트랜잭션은 서로 다른 책임이므로, 자기 주입(self-injection)으로 프록시를
 * 우회하는 대신 컴포넌트를 분리했습니다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SettlementRecorder {

    /** 이 서비스가 적재하는 정산의 기관 코드. */
    public static final String INSTITUTION_CODE = "PG";

    private final PgSettlementAdapter pgSettlementAdapter;
    private final ExternalSettlementRepository settlementRepository;

    /**
     * 외부 거래 ID 한 건을 조회해 적재합니다. 이미 적재된 건은 건너뜁니다.
     *
     * @param externalTransactionId 외부 거래 ID
     * @return 신규 적재 여부
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean record(String externalTransactionId) {
        if (settlementRepository.findByInstitutionCodeAndExternalReferenceId(
                INSTITUTION_CODE, externalTransactionId).isPresent()) {
            return false;
        }

        ExternalSettlementDto dto = pgSettlementAdapter.fetchSettlement(externalTransactionId);
        if (dto == null) {
            log.warn("PG 정산 응답이 비어 있습니다. transactionId={}", externalTransactionId);
            return false;
        }

        // 정산 금액은 수수료를 차감한 실수령액으로 계산한다.
        var netAmount = dto.fee() != null ? dto.amount().subtract(dto.fee()) : dto.amount();

        ExternalSettlement settlement = ExternalSettlement.create(
                dto.transactionId(),
                INSTITUTION_CODE,
                dto.settledAt(),
                "PG settlement " + dto.transactionId() + " (" + dto.status() + ")",
                Money.of(netAmount, AssetType.FIAT, dto.currency())
        );

        try {
            // save() 는 INSERT 를 커밋 시점(메서드 밖, REQUIRES_NEW 프록시)까지 미루므로
            // 아래 catch 가 절대 잡을 수 없다. saveAndFlush() 로 즉시 flush 해 유니크 제약
            // 위반이 이 메서드 안에서 발생하게 만든다. 그렇지 않으면 동시 적재의 정상적인
            // 중복이 호출자에서 "적재 실패"로 집계된다.
            settlementRepository.saveAndFlush(settlement);
            return true;
        } catch (DataIntegrityViolationException e) {
            // 다른 노드가 먼저 적재한 경우. 유니크 제약이 최종 방어선이다.
            log.debug("정산 내역이 이미 적재되어 있습니다. transactionId={}", externalTransactionId);
            return false;
        }
    }
}
