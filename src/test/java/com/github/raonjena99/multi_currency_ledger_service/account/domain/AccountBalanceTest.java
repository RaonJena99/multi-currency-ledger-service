package com.github.raonjena99.multi_currency_ledger_service.account.domain;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

public class AccountBalanceTest {

    @Test
    @DisplayName("다중 매수 시 MAC이 정확하게 가중 평균되어 계산된다")
    void calculate_mac_correctly() {
        // given
        AccountBalance balance = new AccountBalance();
        ReflectionTestUtils.setField(balance, "accountId", UUID.randomUUID());
        ReflectionTestUtils.setField(balance, "assetCode", "AAPL");
        ReflectionTestUtils.setField(balance, "balance", BigDecimal.ZERO);
        ReflectionTestUtils.setField(balance, "averageUnitPrice", BigDecimal.ZERO);

        // 1차 매수: 100주 @ 10$ -> 평균 10$
        balance.addBalance(BigDecimal.valueOf(100), BigDecimal.valueOf(10));
        assertThat(balance.getAverageUnitPrice()).isEqualByComparingTo("10");

        // 2차 매수: 100주 @ 20$ -> (1000 + 2000) / 200 = 15$
        balance.addBalance(BigDecimal.valueOf(100), BigDecimal.valueOf(20));
        assertThat(balance.getAverageUnitPrice()).isEqualByComparingTo("15");
    }

    @Test
    @DisplayName("부분 매도 시 평균 단가는 유지되며, 실현 손익 계산을 위해 기존 단가를 반환한다")
    void maintain_mac_on_sell() {
        // given
        AccountBalance balance = new AccountBalance();
        ReflectionTestUtils.setField(balance, "balance", BigDecimal.ZERO);
        ReflectionTestUtils.setField(balance, "averageUnitPrice", BigDecimal.ZERO);
        balance.addBalance(BigDecimal.valueOf(100), BigDecimal.valueOf(10));

        // when (50주 매도)
        BigDecimal returnedAvgCost = balance.subtractBalance(BigDecimal.valueOf(50));

        // then
        assertThat(returnedAvgCost).isEqualByComparingTo("10"); // 실현 손익을 계산할 기준 단가 반환
        assertThat(balance.getBalance()).isEqualByComparingTo("50");
        assertThat(balance.getAverageUnitPrice()).isEqualByComparingTo("10"); // 잔여 자산의 평단가는 변하지 않음
    }
    
}
