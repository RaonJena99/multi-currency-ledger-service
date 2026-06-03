package com.github.raonjena99.multi_currency_ledger_service.repository;

import com.github.raonjena99.multi_currency_ledger_service.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AccountRepositoryTest extends IntegrationTestSupport {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("Flyway 마이그레이션이 정상 적용되고 UUID PK로 계좌가 저장된다")
    void flyway_migration_and_insert_test() {
        // given
        UUID accountId = UUID.randomUUID();
        String ownerName = "Test Owner";

        // when
        int result = jdbcTemplate.update(
                "INSERT INTO accounts (id, owner_name, status) VALUES (?, ?, ?)",
                accountId, ownerName, "ACTIVE"
        );

        // then
        assertThat(result).isEqualTo(1);
        
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM accounts WHERE id = ?", 
                Integer.class, 
                accountId
        );
        assertThat(count).isEqualTo(1);
    }
}
