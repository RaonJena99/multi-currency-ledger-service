package com.github.raonjena99.multi_currency_ledger_service.reconciliation.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.github.raonjena99.multi_currency_ledger_service.common.model.SettlementStatus;

class ExternalSettlementTest {

    @Test
    @DisplayName("정상적으로 매칭된 정산 건은 MATCHED 상태로 전이되어야 한다.")
    void markAsMatched_ChangesStatusToMatched() {
        ExternalSettlement settlement = ExternalSettlement.builder()
                .transactionId("TX-999")
                .amount(BigDecimal.valueOf(1000))
                .status(SettlementStatus.PENDING)
                .build();

        settlement.markAsMatched("INTERNAL-TX-999");

        assertThat(settlement.getStatus()).isEqualTo(SettlementStatus.MATCHED);
        assertThat(settlement.getMatchedInternalId()).isEqualTo("INTERNAL-TX-999");
    }

    @Test
    @DisplayName("이미 MATCHED 상태인 정산 건을 다시 MISMATCHED로 변경하려 하면 멱등성 보호를 위해 예외가 발생한다.")
    void changeStatus_WhenAlreadyMatched_ThrowsException() {
        ExternalSettlement settlement = ExternalSettlement.builder()
                .transactionId("TX-999")
                .status(SettlementStatus.MATCHED)
                .build();

        assertThatThrownBy(() -> settlement.markAsMismatched("Amount differs"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot change status");
    }
}