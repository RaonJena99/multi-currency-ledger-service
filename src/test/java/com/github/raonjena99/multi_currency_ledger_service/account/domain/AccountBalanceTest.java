package com.github.raonjena99.multi_currency_ledger_service.account.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.github.raonjena99.multi_currency_ledger_service.common.domain.Money;
import com.github.raonjena99.multi_currency_ledger_service.common.model.AssetType;

@DisplayName("도메인 단위 테스트: AccountBalance (다중 자산 평균 단가 및 잔액 관리)")
class AccountBalanceTest {

    @Test
    @DisplayName("최초 매수 시 잔액이 증가하고 해당 단가가 평균 매입 단가로 설정된다.")
    void addBalance_initial_buy() {
        // given
        UUID accountId = UUID.randomUUID();
        AccountBalance balance = new AccountBalance(accountId, "BTC", AssetType.CRYPTO);
        Money quantity = Money.of("0.5", AssetType.CRYPTO);
        Money unitPrice = Money.of("100000000", AssetType.FIAT);

        // when
        balance.addBalance(quantity, unitPrice);

        // then
        assertThat(balance.getBalance().getAmount()).isEqualByComparingTo("0.5");
        assertThat(balance.getAverageUnitPrice().getAmount()).isEqualByComparingTo("100000000");
    }

    @Test
    @DisplayName("물타기(추가 매수) 시, 이동 평균 단가가 총 가치 기반으로 정확히 재계산된다.")
    void addBalance_average_cost_recalculation() {
        // given
        AccountBalance balance = new AccountBalance(UUID.randomUUID(), "AAPL", AssetType.STOCK);
        
        // 1차 매수: 10주, 단가 150달러 (총 1500)
        balance.addBalance(Money.of("10", AssetType.STOCK), Money.of("150", AssetType.FIAT));
        
        // 2차 매수: 20주, 단가 120달러 (총 2400)
        // 총 수량 = 30주, 총 가치 = 3900 -> 예상 평균 단가 = 130
        
        // when
        balance.addBalance(Money.of("20", AssetType.STOCK), Money.of("120", AssetType.FIAT));

        // then
        assertThat(balance.getBalance().getAmount()).isEqualByComparingTo("30");
        assertThat(balance.getAverageUnitPrice().getAmount()).isEqualByComparingTo("130");
    }

    @Test
    @DisplayName("부분 매도 시 잔액은 차감되지만, 평균 매입 단가는 변하지 않는다.")
    void subtractBalance_partial_sell_keeps_average_cost() {
        // given
        AccountBalance balance = new AccountBalance(UUID.randomUUID(), "TSLA", AssetType.STOCK);
        balance.addBalance(Money.of("10", AssetType.STOCK), Money.of("200", AssetType.FIAT));

        // when
        Money returnedCost = balance.subtractBalance(Money.of("4", AssetType.STOCK));

        // then
        assertThat(balance.getBalance().getAmount()).isEqualByComparingTo("6");
        assertThat(balance.getAverageUnitPrice().getAmount()).isEqualByComparingTo("200");
        assertThat(returnedCost.getAmount()).isEqualByComparingTo("200");
    }

    @Test
    @DisplayName("전량 매도 시 평균 매입 단가는 0으로 완벽히 초기화된다.")
    void subtractBalance_total_sell_resets_average_cost() {
        // given
        AccountBalance balance = new AccountBalance(UUID.randomUUID(), "NVDA", AssetType.STOCK);
        balance.addBalance(Money.of("5", AssetType.STOCK), Money.of("800", AssetType.FIAT));

        // when (전량 매도)
        Money returnedCost = balance.subtractBalance(Money.of("5", AssetType.STOCK));

        // then
        assertThat(balance.getBalance().getAmount()).isEqualByComparingTo("0");
        assertThat(balance.getAverageUnitPrice().getAmount()).isEqualByComparingTo("0");
        assertThat(returnedCost.getAmount()).isEqualByComparingTo("800"); // 매도 당시의 평단가 반환 확인
    }

    @Test
    @DisplayName("보유 잔액보다 많은 수량을 매도 시도하면 예외가 발생한다.")
    void subtractBalance_insufficient_throws_exception() {
        // given
        AccountBalance balance = new AccountBalance(UUID.randomUUID(), "ETH", AssetType.CRYPTO);
        balance.addBalance(Money.of("5", AssetType.CRYPTO), Money.of("3000000", AssetType.FIAT));

        // when & then
        assertThatThrownBy(() -> balance.subtractBalance(Money.of("6", AssetType.CRYPTO)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Insufficient balance");
    }
}