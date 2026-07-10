package com.github.raonjena99.multi_currency_ledger_service.account.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class AccountTest {

    @Test
    @DisplayName("isNew - createdAt이 null이면 true, 아니면 false 반환")
    void isNew_check() {
        Account account = new Account(UUID.randomUUID(), "Test Owner");
        assertThat(account.isNew()).isTrue();

        ReflectionTestUtils.setField(account, "createdAt", OffsetDateTime.now());
        assertThat(account.isNew()).isFalse();
    }
}
