package com.github.raonjena99.multi_currency_ledger_service.account.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.github.raonjena99.multi_currency_ledger_service.common.domain.Money;
import com.github.raonjena99.multi_currency_ledger_service.common.model.AssetType;

@DisplayName("도메인 단위 테스트: MonthlyAccountLedger (월차 원장 및 이월 로직 검증)")
class MonthlyAccountLedgerTest {

    @Test
    @DisplayName("최초로 자산을 매수할 때 정상적으로 월차 원장이 초기화된다.")
    void initialize_new_ledger() {
        UUID accountId = UUID.randomUUID();
        MonthlyAccountLedger ledger = new MonthlyAccountLedger(accountId, "BTC", AssetType.CRYPTO, "2026-05");

        assertThat(ledger.getAccountId()).isEqualTo(accountId);
        assertThat(ledger.getLedgerMonth()).isEqualTo("2026-05");
        assertThat(ledger.isCarriedForward()).isFalse();
        assertThat(ledger.getBalance().getAmount()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("이전 달의 장부를 기반으로 당월 장부로 이월(Rollover) 처리 시, 잔액과 평단가가 정확히 복사된다.")
    void carry_forward_logic() {
        // given
        UUID accountId = UUID.randomUUID();
        MonthlyAccountLedger prevLedger = new MonthlyAccountLedger(accountId, "ETH", AssetType.CRYPTO, "2026-04");
        prevLedger.addBalance(Money.of("10", AssetType.CRYPTO), Money.of("3000000", AssetType.FIAT));

        // when
        MonthlyAccountLedger newLedger = MonthlyAccountLedger.carryForwardFrom(prevLedger, "2026-05");

        // then
        assertThat(newLedger.getLedgerMonth()).isEqualTo("2026-05");
        assertThat(newLedger.isCarriedForward()).isTrue(); // 이월 플래그 활성화 검증
        assertThat(newLedger.getBalance().getAmount()).isEqualByComparingTo("10"); // 잔액 유지 검증
        assertThat(newLedger.getAverageUnitPrice().getAmount()).isEqualByComparingTo("3000000"); // 평단가 유지 검증
    }
}
