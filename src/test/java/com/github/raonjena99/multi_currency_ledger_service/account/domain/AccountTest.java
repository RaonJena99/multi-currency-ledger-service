package com.github.raonjena99.multi_currency_ledger_service.account.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AccountTest {

    @Test
    @DisplayName("정상적인 입금 처리가 계좌 잔고를 증가시켜야 한다.")
    void deposit_ValidAmount_IncreasesBalance() {
        Account account = Account.builder()
                .accountId("ACC-123")
                .currency("KRW")
                .balance(BigDecimal.ZERO)
                .build();

        account.deposit(BigDecimal.valueOf(50000));

        assertThat(account.getBalance()).isEqualByComparingTo("50000");
    }

    @Test
    @DisplayName("잔고를 초과하는 출금을 시도할 경우 예외가 발생하여 초과 출금을 방지한다.")
    void withdraw_ExceedingBalance_ThrowsException() {
        Account account = Account.builder()
                .accountId("ACC-123")
                .currency("KRW")
                .balance(BigDecimal.valueOf(10000))
                .build();

        assertThatThrownBy(() -> account.withdraw(BigDecimal.valueOf(15000)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Insufficient balance"); // 실제 예외 메시지에 맞게 수정 필요
    }
}