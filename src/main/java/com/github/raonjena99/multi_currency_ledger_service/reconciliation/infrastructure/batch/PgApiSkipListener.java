package com.github.raonjena99.multi_currency_ledger_service.reconciliation.infrastructure.batch;

import org.springframework.batch.core.listener.SkipListener;
import org.springframework.stereotype.Component;

import com.github.raonjena99.multi_currency_ledger_service.reconciliation.application.batch.MatchedReconciliationResult;
import com.github.raonjena99.multi_currency_ledger_service.reconciliation.domain.ExternalSettlement;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class PgApiSkipListener implements SkipListener<ExternalSettlement, MatchedReconciliationResult> {

    @Override
    public void onSkipInProcess(ExternalSettlement item, Throwable t) {
        log.warn("[Batch Skip] PG 데이터 Fetch 실패로 대사 검증이 다음 주기로 이관됩니다. ExternalReferenceID: {} | 사유: {}", 
                item.getExternalReferenceId(), t.getMessage());
    }
    
    @Override
    public void onSkipInRead(Throwable t) {
        log.warn("[Batch Skip] 읽기 단계에서 예외 발생. 사유: {}", t.getMessage());
    }

    @Override
    public void onSkipInWrite(MatchedReconciliationResult item, Throwable t) {
        log.warn("[Batch Skip] 대사 결과 쓰기 단계에서 예외 발생. InternalTxID: {} | 사유: {}", 
                item.matchedTransactionId(), t.getMessage());
    }
}
