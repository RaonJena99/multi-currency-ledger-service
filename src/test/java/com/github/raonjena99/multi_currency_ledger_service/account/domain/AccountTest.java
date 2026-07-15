package com.github.raonjena99.multi_currency_ledger_service.account.domain;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.assertThat;

class AccountTest {
    @Test
    @DisplayName("isNew check")
    void isNew_check() {
        Account account = Account.open(UUID.randomUUID(), "Test Owner", "KRW");
        assertThat(account.isNew()).isTrue();
        ReflectionTestUtils.setField(account, "createdAt", OffsetDateTime.now());
        assertThat(account.isNew()).isFalse();
    }
}
