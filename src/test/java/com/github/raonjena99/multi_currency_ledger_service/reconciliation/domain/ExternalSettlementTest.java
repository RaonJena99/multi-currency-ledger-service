package com.github.raonjena99.multi_currency_ledger_service.reconciliation.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.github.raonjena99.multi_currency_ledger_service.common.domain.Money;
import com.github.raonjena99.multi_currency_ledger_service.common.model.AssetType;

class ExternalSettlementTest {

    @Test
    @DisplayName("상태 전이 예외 상황 검증")
    void state_transition_exceptions() {
        ExternalSettlement settlement = ExternalSettlement.create("REF1", "TOSS", OffsetDateTime.now(), "DESC", Money.of("1000", AssetType.FIAT));
        
        // PENDING 상태에서 수동 해결 불가
        assertThatThrownBy(() -> settlement.resolveManually(UUID.randomUUID()))
            .isInstanceOf(IllegalStateException.class);
            
        // PENDING -> MATCHED (정상)
        settlement.markAsMatched(UUID.randomUUID());
        
        // MATCHED 상태에서 UNMATCHED 전환 불가
        assertThatThrownBy(settlement::markAsUnmatched)
            .isInstanceOf(IllegalStateException.class);
            
        // MATCHED 상태에서 다시 MATCHED 불가 (PENDING, UNMATCHED만 가능)
        assertThatThrownBy(() -> settlement.markAsMatched(UUID.randomUUID()))
            .isInstanceOf(IllegalStateException.class);
    }
    
    @Test
    @DisplayName("isNew 검증")
    void isNew_check() {
        ExternalSettlement settlement = ExternalSettlement.create("REF1", "TOSS", OffsetDateTime.now(), "DESC", Money.of("1000", AssetType.FIAT));
        assertThat(settlement.isNew()).isTrue();
        
        ReflectionTestUtils.setField(settlement, "createdAt", OffsetDateTime.now());
        assertThat(settlement.isNew()).isFalse();
    }
}
