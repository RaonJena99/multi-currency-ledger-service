package com.github.raonjena99.multi_currency_ledger_service.regression;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.github.raonjena99.multi_currency_ledger_service.IntegrationTestSupport;
import com.github.raonjena99.multi_currency_ledger_service.account.domain.Account;
import com.github.raonjena99.multi_currency_ledger_service.account.infrastructure.AccountRepository;
import com.github.raonjena99.multi_currency_ledger_service.common.domain.Money;
import com.github.raonjena99.multi_currency_ledger_service.common.model.AssetType;
import com.github.raonjena99.multi_currency_ledger_service.transaction.domain.Transaction;
import com.github.raonjena99.multi_currency_ledger_service.transaction.infrastructure.TransactionRepository;

/**
 * 스키마가 도메인 동작과 어긋나지 않는지 검증합니다.
 *
 * <p>{@code ddl-auto: validate} 는 컬럼 존재만 확인하고 CHECK 제약이나 인덱스는 검증하지 않습니다.
 * 그래서 "엔티티에 선언되어 있으니 DB 에도 있겠지" 라는 가정이 조용히 깨질 수 있습니다.
 */
@DisplayName("회귀 테스트: 스키마와 도메인 정합성")
class SchemaGuardTest extends IntegrationTestSupport {

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private AccountRepository accountRepository;

    @Test
    @DisplayName("반올림이 발생하는 분개도 CHECK 제약을 통과한다")
    void rounded_entry_passes_check_constraint() {
        UUID accountId = UUID.randomUUID();
        accountRepository.saveAndFlush(Account.open(accountId, "SCHEMA", "KRW"));

        // KRW 스케일은 0 이므로 amount 는 100 으로 정규화되지만 원식은 100.4 다.
        // 예전 chk_amount_calculation 은 정확 일치를 요구해 이 분개를 거부했다.
        Transaction tx = Transaction.record(UUID.randomUUID(), "BUY", "rounding");
        tx.addBuyEntry(accountId, "BTC", Money.of("1", AssetType.CRYPTO, "BTC"),
                new BigDecimal("100.4"), BigDecimal.ONE, "KRW");
        tx.addSellEntry(accountId, "KRW", Money.of("100.4", AssetType.FIAT, "KRW"),
                BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, "KRW");

        transactionRepository.saveAndFlush(tx);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM transaction_entries WHERE transaction_id = ?", Integer.class, tx.getId()))
                .isEqualTo(2);
    }

    @Test
    @DisplayName("소수점이 긴 환율도 절삭 없이 저장되어 대차가 어긋나지 않는다")
    void high_precision_exchange_rate_is_preserved() {
        UUID accountId = UUID.randomUUID();
        accountRepository.saveAndFlush(Account.open(accountId, "SCHEMA2", "KRW"));

        BigDecimal rate = new BigDecimal("1300.1234567890");

        Transaction tx = Transaction.record(UUID.randomUUID(), "BUY", "precision");
        tx.addBuyEntry(accountId, "AAPL", Money.of("1", AssetType.STOCK, "AAPL"),
                new BigDecimal("10"), rate, "KRW");
        tx.addSellEntry(accountId, "USD", Money.of("10", AssetType.FIAT, "USD"),
                BigDecimal.ONE, rate, BigDecimal.ONE, "KRW");

        transactionRepository.saveAndFlush(tx);

        List<BigDecimal> rates = jdbcTemplate.queryForList(
                "SELECT exchange_rate FROM transaction_entries WHERE transaction_id = ?",
                BigDecimal.class, tx.getId());

        assertThat(rates)
                .as("numeric(19,6) 이면 1300.123457 로 절삭되어 amount 와 어긋난다")
                .allSatisfy(r -> assertThat(r).isEqualByComparingTo(rate));
    }

    @Test
    @DisplayName("멱등성 키 정리용 created_at 인덱스가 실제로 존재한다")
    void idempotency_created_at_index_exists() {
        List<String> indexes = jdbcTemplate.queryForList(
                "SELECT indexname FROM pg_indexes WHERE tablename = 'idempotency_records'", String.class);

        assertThat(indexes)
                .as("인덱스가 없으면 매일 밤 정리 작업이 풀 스캔한다")
                .contains("idx_idempotency_created_at");
    }

    @Test
    @DisplayName("정산 매칭 1:1 제약이 DB 에 실제로 존재한다")
    void settlement_match_enforces_one_to_one() {
        List<Map<String, Object>> constraints = jdbcTemplate.queryForList(
                "SELECT conname, contype FROM pg_constraint WHERE conrelid = 'settlement_match'::regclass");

        assertThat(constraints)
                .as("internal_transaction_id 가 PK 여야 같은 내부 거래의 중복 매칭을 DB 가 막는다")
                .anySatisfy(c -> assertThat(c.get("contype")).isEqualTo("p"));

        UUID internalTxId = UUID.randomUUID();
        java.time.OffsetDateTime now = java.time.OffsetDateTime.now();

        jdbcTemplate.update("INSERT INTO settlement_match "
                + "(internal_transaction_id, external_settlement_id, settlement_date, matched_at, created_at, updated_at) "
                + "VALUES (?, ?, ?, ?, now(), now())", internalTxId, UUID.randomUUID(), now, now);

        // 같은 내부 거래를 다른 정산에 매칭하려는 시도는 DB 가 거부해야 한다.
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                jdbcTemplate.update("INSERT INTO settlement_match "
                        + "(internal_transaction_id, external_settlement_id, settlement_date, matched_at, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, now(), now())", internalTxId, UUID.randomUUID(), now, now))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);

        jdbcTemplate.update("DELETE FROM settlement_match WHERE internal_transaction_id = ?", internalTxId);
    }

    @Test
    @DisplayName("원장 실패 격리 테이블이 존재한다")
    void ledger_dead_letter_table_exists() {
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM information_schema.tables WHERE table_name = 'ledger_dead_letters'",
                Integer.class))
                .isEqualTo(1);
    }
}
