package com.github.raonjena99.multi_currency_ledger_service.regression;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.sql.Connection;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import com.github.raonjena99.multi_currency_ledger_service.IntegrationTestSupport;
import com.github.raonjena99.multi_currency_ledger_service.account.domain.Account;
import com.github.raonjena99.multi_currency_ledger_service.account.infrastructure.AccountRepository;
import com.github.raonjena99.multi_currency_ledger_service.transaction.application.LedgerQueryService;

/**
 * 실현 손익을 별도 분개로 옮기기 전에 기록된 분개에 손익 분개를 채우는 마이그레이션을 검증합니다.
 *
 * <p>Flyway 는 빈 DB 에서 이미 이 스크립트를 실행했습니다. 여기서는 예전 방식(손익이 {@code realized_pnl}
 * 컬럼에만 있고 손익 분개가 없는 분개)을 직접 넣은 뒤 같은 스크립트를 다시 실행해, 운영 DB 에 처음 적용될 때와
 * 같은 상황을 재현합니다.
 */
@DisplayName("회귀 테스트: 실현 손익 분개 백필 마이그레이션")
class RealizedPnlBackfillMigrationTest extends IntegrationTestSupport {

    private static final String SCRIPT = "db/migration/V202610070001__create_realized_pnl_entries.sql";
    private static final OffsetDateTime TRADED_AT = OffsetDateTime.now(ZoneOffset.UTC);
    private static final YearMonth MONTH = YearMonth.from(TRADED_AT);
    private static final UUID CASH_CLEARING = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Autowired private DataSource dataSource;
    @Autowired private AccountRepository accountRepository;
    @Autowired private LedgerQueryService queryService;
    @Autowired private JdbcTemplate jdbc;

    private final UUID customer = UUID.randomUUID();
    /** 이익 매도: 대변 BTC 원가 1,600 + realized_pnl 400 */
    private final UUID profitSell = UUID.randomUUID();
    /** 손실 매도: 대변 BTC 원가 1,000 + realized_pnl -100 */
    private final UUID lossSell = UUID.randomUUID();
    /** 외화 출금: 대변 USD 원가 52,000 + realized_pnl 4,000 */
    private final UUID withdrawal = UUID.randomUUID();
    /** 새 코드가 기록한 거래: 손익 분개가 이미 있으므로 건너뛴다 */
    private final UUID alreadyMigrated = UUID.randomUUID();
    /** 손익이 없는 거래 */
    private final UUID noPnl = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        accountRepository.saveAndFlush(Account.open(customer, "LEGACY_PNL", "KRW"));

        transaction(profitSell, "SELL");
        entry(profitSell, customer, "DEBIT", "KRW", "FIAT", "2000", "KRW", "1", "1", "2000", "0");
        entry(profitSell, customer, "CREDIT", "BTC", "CRYPTO", "2", "BTC", "800", "1", "1600", "400");

        transaction(lossSell, "SELL");
        entry(lossSell, customer, "DEBIT", "KRW", "FIAT", "900", "KRW", "1", "1", "900", "0");
        entry(lossSell, customer, "CREDIT", "BTC", "CRYPTO", "1", "BTC", "1000", "1", "1000", "-100");

        transaction(withdrawal, "WITHDRAWAL");
        entry(withdrawal, CASH_CLEARING, "DEBIT", "USD", "FIAT", "40", "USD", "1", "1400", "56000", "0");
        entry(withdrawal, customer, "CREDIT", "USD", "FIAT", "40", "USD", "1", "1300", "52000", "4000");

        transaction(alreadyMigrated, "SELL");
        entry(alreadyMigrated, customer, "DEBIT", "KRW", "FIAT", "2000", "KRW", "1", "1", "2000", "0");
        entry(alreadyMigrated, customer, "CREDIT", "BTC", "CRYPTO", "2", "BTC", "800", "1", "1600", "400");
        entry(alreadyMigrated, customer, "CREDIT", "REALIZED_PNL_TRADING", "FIAT", "400", "KRW", "1", "1", "400", "0");

        transaction(noPnl, "DEPOSIT");
        entry(noPnl, customer, "DEBIT", "KRW", "FIAT", "5000", "KRW", "1", "1", "5000", "0");
        entry(noPnl, CASH_CLEARING, "CREDIT", "KRW", "FIAT", "5000", "KRW", "1", "1", "5000", "0");
    }

    @AfterEach
    void tearDown() {
        jdbc.execute("TRUNCATE TABLE transaction_entries, transactions CASCADE");
        deleteTestAccounts();
    }

    private void transaction(UUID id, String type) {
        jdbc.update("INSERT INTO transactions (id, transaction_type, transacted_at, description) VALUES (?, ?, ?, ?)",
                id, type, TRADED_AT, "legacy " + type);
    }

    private void entry(UUID transactionId, UUID accountId, String entryType, String assetCode, String assetType,
                       String quantity, String quantityCurrency, String unitPrice, String rate, String amount,
                       String pnl) {
        jdbc.update("INSERT INTO transaction_entries (transaction_id, account_id, entry_type, asset_code, "
                + "quantity_asset_type, quantity, quantity_currency, unit_price, exchange_rate, amount, "
                + "amount_asset_type, amount_currency, realized_pnl, realized_pnl_asset_type, realized_pnl_currency) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'FIAT', 'KRW', ?, 'FIAT', 'KRW')",
                transactionId, accountId, entryType, assetCode, assetType, new BigDecimal(quantity), quantityCurrency,
                new BigDecimal(unitPrice), new BigDecimal(rate), new BigDecimal(amount), new BigDecimal(pnl));
    }

    private void runMigration() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource(SCRIPT));
        }
    }

    private List<Map<String, Object>> pnlEntries(UUID transactionId) {
        return jdbc.queryForList("SELECT account_id, entry_type, asset_code, quantity, quantity_currency, unit_price, "
                + "exchange_rate, amount, amount_currency, realized_pnl FROM transaction_entries "
                + "WHERE transaction_id = ? AND asset_code LIKE 'REALIZED\\_PNL\\_%' ORDER BY id", transactionId);
    }

    private int entryCount() {
        return jdbc.queryForObject("SELECT count(*) FROM transaction_entries", Integer.class);
    }

    private boolean krwBalanced() {
        return queryService.trialBalance(MONTH).currencies().stream()
                .filter(c -> "KRW".equals(c.currency())).findFirst().orElseThrow().balanced();
    }

    @Test
    @DisplayName("realized_pnl 이 있는 분개마다 고객 계정의 손익 분개를 추가한다")
    void addsAPnlEntryForEveryEntryWithRealizedPnl() throws Exception {
        runMigration();

        assertThat(pnlEntries(profitSell)).singleElement().satisfies(r -> {
            assertThat(r.get("account_id")).isEqualTo(customer);
            assertThat(r.get("entry_type")).isEqualTo("CREDIT");
            assertThat(r.get("asset_code")).isEqualTo("REALIZED_PNL_TRADING");
            assertThat((BigDecimal) r.get("quantity")).isEqualByComparingTo("400");
            assertThat(r.get("quantity_currency")).isEqualTo("KRW");
            assertThat((BigDecimal) r.get("unit_price")).isEqualByComparingTo("1");
            assertThat((BigDecimal) r.get("exchange_rate")).isEqualByComparingTo("1");
            assertThat((BigDecimal) r.get("amount")).isEqualByComparingTo("400");
            assertThat(r.get("amount_currency")).isEqualTo("KRW");
            assertThat((BigDecimal) r.get("realized_pnl")).isEqualByComparingTo("0");
        });
    }

    @Test
    @DisplayName("손실은 차변 분개로 만든다")
    void lossBecomesADebitEntry() throws Exception {
        runMigration();

        assertThat(pnlEntries(lossSell)).singleElement().satisfies(r -> {
            assertThat(r.get("entry_type")).isEqualTo("DEBIT");
            assertThat(r.get("asset_code")).isEqualTo("REALIZED_PNL_TRADING");
            assertThat((BigDecimal) r.get("amount")).isEqualByComparingTo("100");
        });
    }

    @Test
    @DisplayName("나가는 쪽이 법정화폐인 손익(외화 출금 등)은 환차손익(FX) 분개로 만든다")
    void fiatOutflowBecomesAnFxEntry() throws Exception {
        runMigration();

        assertThat(pnlEntries(withdrawal)).singleElement().satisfies(r -> {
            assertThat(r.get("account_id")).as("손익은 청산 계정이 아니라 고객 계정에 귀속된다").isEqualTo(customer);
            assertThat(r.get("entry_type")).isEqualTo("CREDIT");
            assertThat(r.get("asset_code")).isEqualTo("REALIZED_PNL_FX");
            assertThat((BigDecimal) r.get("amount")).isEqualByComparingTo("4000");
        });
    }

    @Test
    @DisplayName("이미 손익 분개가 있는 거래와 손익이 없는 거래는 건드리지 않는다")
    void skipsMigratedAndPnlFreeTransactions() throws Exception {
        runMigration();

        assertThat(pnlEntries(alreadyMigrated)).hasSize(1);
        assertThat(pnlEntries(noPnl)).isEmpty();
    }

    @Test
    @DisplayName("기존 분개 행은 수정하지 않고 분개만 더한다")
    void doesNotModifyExistingRows() throws Exception {
        List<Map<String, Object>> before = jdbc.queryForList(
                "SELECT id, entry_type, asset_code, quantity, amount, realized_pnl FROM transaction_entries ORDER BY id");

        runMigration();

        List<Map<String, Object>> after = jdbc.queryForList(
                "SELECT id, entry_type, asset_code, quantity, amount, realized_pnl FROM transaction_entries "
                        + "WHERE id <= ? ORDER BY id", ((Number) before.get(before.size() - 1).get("id")).longValue());
        assertThat(after).isEqualTo(before);
    }

    @Test
    @DisplayName("마이그레이션 뒤에는 옛 분개도 차변 = 대변 이다")
    void trialBalanceBalancesAfterMigration() throws Exception {
        assertThat(krwBalanced()).as("마이그레이션 전: 손익 분개가 없어 옛 분개는 차변 ≠ 대변").isFalse();

        runMigration();

        assertThat(krwBalanced()).isTrue();
    }

    @Test
    @DisplayName("다시 실행해도 분개가 늘지 않는다")
    void isIdempotent() throws Exception {
        runMigration();
        int afterFirstRun = entryCount();

        runMigration();

        assertThat(entryCount()).isEqualTo(afterFirstRun);
    }
}
