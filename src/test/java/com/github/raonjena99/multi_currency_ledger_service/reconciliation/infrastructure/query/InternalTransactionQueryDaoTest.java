package com.github.raonjena99.multi_currency_ledger_service.reconciliation.infrastructure.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

class InternalTransactionQueryDaoTest {

    @Test
    void fetchCandidatesForPeriod_nullTimestamp() throws Exception {
        NamedParameterJdbcTemplate jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        InternalTransactionQueryDao dao = new InternalTransactionQueryDao(jdbcTemplate);
        
        ResultSet rs = mock(ResultSet.class);
        when(rs.getTimestamp("transacted_at")).thenReturn(null);
        when(rs.getString("transaction_id")).thenReturn(UUID.randomUUID().toString());
        when(rs.getString("account_id")).thenReturn(UUID.randomUUID().toString());
        when(rs.getString("description")).thenReturn("desc");
        when(rs.getBigDecimal("amount")).thenReturn(BigDecimal.TEN);
        when(rs.getString("asset_type")).thenReturn("FIAT");
        when(rs.getString("currency")).thenReturn("KRW");

        // mock jdbcTemplate behavior
        when(jdbcTemplate.query(any(String.class), any(org.springframework.jdbc.core.namedparam.SqlParameterSource.class), any(RowMapper.class)))
            .thenAnswer(invocation -> {
                RowMapper<InternalTransactionCandidate> mapper = invocation.getArgument(2);
                return List.of(mapper.mapRow(rs, 1));
            });

        List<InternalTransactionCandidate> list = dao.fetchCandidatesForPeriod(OffsetDateTime.now(), OffsetDateTime.now());
        assertThat(list).isNotEmpty();
        assertThat(list.get(0).transactedAt()).isNull();
    }

    @Test
    void fetchCandidatesForPeriod_withTimestamp() throws Exception {
        NamedParameterJdbcTemplate jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        InternalTransactionQueryDao dao = new InternalTransactionQueryDao(jdbcTemplate);
        
        ResultSet rs = mock(ResultSet.class);
        java.sql.Timestamp now = new java.sql.Timestamp(System.currentTimeMillis());
        when(rs.getTimestamp("transacted_at")).thenReturn(now);
        when(rs.getString("transaction_id")).thenReturn(UUID.randomUUID().toString());
        when(rs.getString("account_id")).thenReturn(UUID.randomUUID().toString());
        when(rs.getString("description")).thenReturn("desc");
        when(rs.getBigDecimal("amount")).thenReturn(BigDecimal.TEN);
        when(rs.getString("asset_type")).thenReturn("FIAT");
        when(rs.getString("currency")).thenReturn("KRW");

        when(jdbcTemplate.query(any(String.class), any(org.springframework.jdbc.core.namedparam.SqlParameterSource.class), any(RowMapper.class)))
            .thenAnswer(invocation -> {
                RowMapper<InternalTransactionCandidate> mapper = invocation.getArgument(2);
                return List.of(mapper.mapRow(rs, 1));
            });

        List<InternalTransactionCandidate> list = dao.fetchCandidatesForPeriod(OffsetDateTime.now(), OffsetDateTime.now());
        assertThat(list).isNotEmpty();
        assertThat(list.get(0).transactedAt()).isNotNull();
    }

    @Test
    void findAccountIdByTransactionId_should_return_uuid() throws Exception {
        NamedParameterJdbcTemplate jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        InternalTransactionQueryDao dao = new InternalTransactionQueryDao(jdbcTemplate);
        UUID expectedId = UUID.randomUUID();
        
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("account_id")).thenReturn(expectedId.toString());

        when(jdbcTemplate.query(any(String.class), any(org.springframework.jdbc.core.namedparam.SqlParameterSource.class), any(RowMapper.class)))
            .thenAnswer(invocation -> {
                RowMapper<?> mapper = invocation.getArgument(2);
                return List.of(mapper.mapRow(rs, 1));
            });
            
        UUID result = dao.findAccountIdByTransactionId(UUID.randomUUID());
        assertThat(result).isEqualTo(expectedId);
    }

    @Test
    void findAccountIdByTransactionId_should_throw_if_empty() {
        NamedParameterJdbcTemplate jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        InternalTransactionQueryDao dao = new InternalTransactionQueryDao(jdbcTemplate);
        when(jdbcTemplate.query(any(String.class), any(org.springframework.jdbc.core.namedparam.SqlParameterSource.class), any(RowMapper.class)))
            .thenReturn(List.of());
            
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> dao.findAccountIdByTransactionId(UUID.randomUUID()))
            .isInstanceOf(IllegalArgumentException.class);
    }
}