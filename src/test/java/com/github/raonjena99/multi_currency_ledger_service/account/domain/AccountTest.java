package com.github.raonjena99.multi_currency_ledger_service.account.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.github.raonjena99.multi_currency_ledger_service.common.exception.InvalidAccountStateException;

class AccountTest {

    @Test
    void open_should_create_active_account_with_valid_parameters() {
        UUID id = UUID.randomUUID();
        Account account = Account.open(id, "Test User", "KRW");

        assertThat(account.getId()).isEqualTo(id);
        assertThat(account.getOwnerName()).isEqualTo("Test User");
        assertThat(account.getBaseCurrency()).isEqualTo("KRW");
        assertThat(account.getStatus()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(account.isActive()).isTrue();
    }

    @Test
    void open_should_throw_exception_for_invalid_parameters() {
        UUID id = UUID.randomUUID();

        assertThatThrownBy(() -> Account.open(null, "Test User", "KRW"))
                .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> Account.open(id, null, "KRW"))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> Account.open(id, "  ", "KRW"))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> Account.open(id, "Test User", null))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> Account.open(id, "Test User", "  "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void suspend_should_change_status_to_suspended() {
        Account account = Account.open(UUID.randomUUID(), "User", "USD");
        account.suspend();

        assertThat(account.getStatus()).isEqualTo(AccountStatus.SUSPENDED);
        assertThat(account.isActive()).isFalse();
    }

    @Test
    void suspend_should_throw_if_closed() {
        Account account = Account.open(UUID.randomUUID(), "User", "USD");
        account.close();

        assertThatThrownBy(account::suspend)
                .isInstanceOf(InvalidAccountStateException.class);
    }

    @Test
    void activate_should_change_status_to_active() {
        Account account = Account.open(UUID.randomUUID(), "User", "USD");
        account.suspend();
        account.activate();

        assertThat(account.getStatus()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(account.isActive()).isTrue();
    }

    @Test
    void activate_should_throw_if_closed() {
        Account account = Account.open(UUID.randomUUID(), "User", "USD");
        account.close();

        assertThatThrownBy(account::activate)
                .isInstanceOf(InvalidAccountStateException.class);
    }

    // 같은 상태로 다시 바꾸는 요청은 거부한다. 받아 주면 의미 없는 상태 변경 이력이 쌓이고,
    // 운영자는 정지가 실제로 적용되었는지(이미 정지였는지) 구분할 수 없다.
    @Test
    void suspend_should_throw_if_already_suspended() {
        Account account = Account.open(UUID.randomUUID(), "User", "USD");
        account.suspend();

        assertThatThrownBy(account::suspend)
                .isInstanceOf(com.github.raonjena99.multi_currency_ledger_service.common.exception.InvalidAccountStateException.class);
    }

    @Test
    void activate_should_throw_if_already_active() {
        Account account = Account.open(UUID.randomUUID(), "User", "USD");

        assertThatThrownBy(account::activate)
                .isInstanceOf(com.github.raonjena99.multi_currency_ledger_service.common.exception.InvalidAccountStateException.class);
    }

    @Test
    void close_should_throw_if_already_closed() {
        Account account = Account.open(UUID.randomUUID(), "User", "USD");
        account.close();

        assertThatThrownBy(account::close)
                .isInstanceOf(com.github.raonjena99.multi_currency_ledger_service.common.exception.InvalidAccountStateException.class);
    }

    @Test
    void isNew_should_return_true_if_created_at_is_null() {
        Account account = Account.open(UUID.randomUUID(), "User", "USD");
        assertThat(account.isNew()).isTrue();
    }

    @Test
    void isNew_should_return_false_if_created_at_is_not_null() {
        Account account = Account.open(UUID.randomUUID(), "User", "USD");
        ReflectionTestUtils.setField(account, "createdAt", java.time.OffsetDateTime.now());
        assertThat(account.isNew()).isFalse();
    }
}
