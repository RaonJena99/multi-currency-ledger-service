package com.github.raonjena99.multi_currency_ledger_service.reconciliation.infrastructure.batch;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.github.raonjena99.multi_currency_ledger_service.reconciliation.application.batch.MatchedReconciliationResult;
import com.github.raonjena99.multi_currency_ledger_service.reconciliation.domain.ExternalSettlement;

class PgApiSkipListenerTest {

    @Test
    void onSkip() {
        PgApiSkipListener listener = new PgApiSkipListener();
        
        ExternalSettlement item = Mockito.mock(ExternalSettlement.class);
        MatchedReconciliationResult result = Mockito.mock(MatchedReconciliationResult.class);

        listener.onSkipInProcess(item, new RuntimeException("test"));
        listener.onSkipInRead(new RuntimeException("test"));
        listener.onSkipInWrite(result, new RuntimeException("test"));
    }
}
