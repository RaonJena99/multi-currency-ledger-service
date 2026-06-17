package com.github.raonjena99.multi_currency_ledger_service.reconciliation.infrastructure.query;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import com.github.raonjena99.multi_currency_ledger_service.IntegrationTestSupport;

@Transactional
@Import(InternalTransactionQueryDao.class)
class InternalTransactionQueryDaoTest extends IntegrationTestSupport {

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private InternalTransactionQueryDao queryDao;

    @Test
    @DisplayName("[QueryDao] 지정된 기간의 CREDIT 분개가 있는 트랜잭션을 DTO로 매핑하여 퍼올린다")
    void fetchCandidatesForPeriod_Success() {
        // Given
        UUID tId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        
        // Accounts 삽입
        jdbcTemplate.update("INSERT INTO accounts (id, owner_name, status) VALUES (?, 'TEST_USER', 'ACTIVE')", accountId);

        // Transactions 삽입
        jdbcTemplate.update("INSERT INTO transactions (id, transaction_type, transacted_at, description) VALUES (?, 'TRADE', '2026-06-15 10:00:00+00', 'TEST')", tId);

        // TransactionEntries 삽입
        jdbcTemplate.update("INSERT INTO transaction_entries (" +
                "transaction_id, account_id, entry_type, asset_code, " +
                "quantity_asset_type, quantity, " +
                "unit_price, unit_price_asset_type, " +
                "amount, amount_asset_type, " +
                "realized_pnl, realized_pnl_asset_type) " +
                "VALUES (?, ?, 'CREDIT', 'KRW', 'FIAT', 1000, 1, 'FIAT', 1000, 'FIAT', 0, 'FIAT')", 
                tId, accountId);

        OffsetDateTime start = OffsetDateTime.parse("2026-06-01T00:00:00Z");
        OffsetDateTime end = OffsetDateTime.parse("2026-07-01T00:00:00Z");

        // When
        List<InternalTransactionCandidate> candidates = queryDao.fetchCandidatesForPeriod(start, end);

        // Then
        assertThat(candidates).hasSize(1);
        assertThat(candidates.get(0).description()).isEqualTo("TEST");
        assertThat(candidates.get(0).amount().getAmount().intValue()).isEqualTo(1000);
    }
}