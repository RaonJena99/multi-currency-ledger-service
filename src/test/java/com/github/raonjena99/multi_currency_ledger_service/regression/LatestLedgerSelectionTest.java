package com.github.raonjena99.multi_currency_ledger_service.regression;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.github.raonjena99.multi_currency_ledger_service.IntegrationTestSupport;
import com.github.raonjena99.multi_currency_ledger_service.account.AccountApi;
import com.github.raonjena99.multi_currency_ledger_service.account.domain.Account;
import com.github.raonjena99.multi_currency_ledger_service.account.infrastructure.AccountRepository;
import com.github.raonjena99.multi_currency_ledger_service.account.infrastructure.MonthlyAccountLedgerRepository;

/**
 * "최신 원장"의 기준이 id 가 아니라 ledgerMonth 인지 검증합니다.
 *
 * <p>id 는 allocationSize=50 인 풀드 시퀀스에서 발급됩니다. 인스턴스가 둘 이상이면 각자 다른 id
 * 구간을 선점하므로 <b>id 순서와 월 순서가 일치하지 않습니다.</b> MAX(id) 로 최신을 고르면 나중에
 * 만들어진 원장이 더 작은 id 를 받아 지난달 잔고가 조회되는 조용한 오류가 발생합니다.
 *
 * <p>이 테스트는 그 상황을 명시적 id 삽입으로 재현합니다.
 */
@DisplayName("회귀 테스트: 최신 원장 선택 기준")
class LatestLedgerSelectionTest extends IntegrationTestSupport {

    @Autowired private AccountApi accountApi;
    @Autowired private AccountRepository accountRepository;
    @Autowired private MonthlyAccountLedgerRepository ledgerRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    @AfterEach
    void tearDown() {
        jdbcTemplate.execute("TRUNCATE TABLE monthly_account_ledgers CASCADE");
        deleteTestAccounts();
    }

    private void insertLedger(long id, UUID accountId, String assetCode, String assetType,
                              String month, String balance, String balanceCurrency) {
        jdbcTemplate.update("INSERT INTO monthly_account_ledgers "
                + "(id, account_id, asset_code, ledger_month, balance, asset_type, balance_currency, "
                + " average_unit_price, average_unit_price_currency, carried_forward, version, created_at, updated_at) "
                + "VALUES (?, ?, ?, ?, ?::numeric, ?, ?, 0, 'KRW', false, 0, now(), now())",
                id, accountId, assetCode, month, balance, assetType, balanceCurrency);
    }

    @Test
    @DisplayName("id 순서가 월 순서와 어긋나도 가장 최신 월의 잔고를 반환한다")
    void picks_latest_month_not_largest_id() {
        UUID accountId = UUID.randomUUID();
        accountRepository.saveAndFlush(Account.open(accountId, "LATEST", "KRW"));

        // 노드 B 가 높은 id 구간으로 더 오래된 달을 먼저 기록
        insertLedger(900_001L, accountId, "BTC", "CRYPTO", "2026-08", "111", "BTC");
        // 노드 A 가 낮은 id 구간으로 더 최신 달을 나중에 기록
        insertLedger(900_000L, accountId, "BTC", "CRYPTO", "2026-09", "222", "BTC");

        var balances = accountApi.getBalances(accountId);

        assertThat(balances).hasSize(1);
        assertThat(balances.get(0).totalQuantity())
                .as("MAX(id) 가 아니라 MAX(ledgerMonth) 를 따라야 한다")
                .isEqualByComparingTo("222");
    }

    @Test
    @DisplayName("자산이 여러 개면 자산별로 각각 최신 월을 고른다")
    void picks_latest_month_per_asset() {
        UUID accountId = UUID.randomUUID();
        accountRepository.saveAndFlush(Account.open(accountId, "LATEST", "KRW"));

        insertLedger(910_005L, accountId, "BTC", "CRYPTO", "2026-07", "1", "BTC");
        insertLedger(910_001L, accountId, "BTC", "CRYPTO", "2026-09", "3", "BTC");
        insertLedger(910_009L, accountId, "KRW", "FIAT", "2026-06", "500", "KRW");
        insertLedger(910_002L, accountId, "KRW", "FIAT", "2026-09", "700", "KRW");

        var balances = accountApi.getBalances(accountId);

        assertThat(balances).hasSize(2);
        assertThat(balances).anySatisfy(b -> {
            assertThat(b.assetCode()).isEqualTo("BTC");
            assertThat(b.totalQuantity()).isEqualByComparingTo("3");
        });
        assertThat(balances).anySatisfy(b -> {
            assertThat(b.assetCode()).isEqualTo("KRW");
            assertThat(b.totalQuantity()).isEqualByComparingTo("700");
        });
    }

    @Test
    @DisplayName("플랫폼 총 잔고 집계도 계좌별 최신 월만 합산한다")
    void sums_only_latest_month_per_account() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        accountRepository.saveAndFlush(Account.open(first, "A", "KRW"));
        accountRepository.saveAndFlush(Account.open(second, "B", "KRW"));

        insertLedger(920_009L, first, "KRW", "FIAT", "2026-08", "100", "KRW");
        insertLedger(920_001L, first, "KRW", "FIAT", "2026-09", "300", "KRW");
        insertLedger(920_007L, second, "KRW", "FIAT", "2026-09", "50", "KRW");

        assertThat(ledgerRepository.sumLatestBalanceByAssetCode("KRW"))
                .as("계좌별 최신 월만 합산: 300 + 50")
                .isEqualByComparingTo("350");
    }
}
