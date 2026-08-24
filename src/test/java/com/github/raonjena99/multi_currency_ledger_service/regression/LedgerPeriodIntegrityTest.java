package com.github.raonjena99.multi_currency_ledger_service.regression;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import com.github.raonjena99.multi_currency_ledger_service.IntegrationTestSupport;
import com.github.raonjena99.multi_currency_ledger_service.account.AccountApi;
import com.github.raonjena99.multi_currency_ledger_service.account.application.LedgerPeriodResolver;
import com.github.raonjena99.multi_currency_ledger_service.account.application.MonthlyLedgerResolver;
import com.github.raonjena99.multi_currency_ledger_service.account.domain.Account;
import com.github.raonjena99.multi_currency_ledger_service.account.domain.MonthlyAccountLedger;
import com.github.raonjena99.multi_currency_ledger_service.account.infrastructure.AccountRepository;
import com.github.raonjena99.multi_currency_ledger_service.account.infrastructure.MonthlyAccountLedgerRepository;
import com.github.raonjena99.multi_currency_ledger_service.common.domain.Money;
import com.github.raonjena99.multi_currency_ledger_service.common.model.AssetType;

/**
 * 월별 원장의 기간 무결성을 고정합니다.
 *
 * <p>두 가지 계약을 지킵니다.
 * <ul>
 *   <li><b>이월은 뒤만 본다.</b> 대상 월보다 미래의 원장에서 잔고를 끌어오면 미래의 거래 결과가
 *       과거 원장의 기초 잔고로 복사됩니다(역방향 이월).</li>
 *   <li><b>쓰기는 읽기가 보는 행을 향한다.</b> 읽기 경로는 {@code MAX(ledger_month)} 행을 읽으므로,
 *       그보다 과거 월에 기장하면 거래가 아무도 읽지 않는 행에 들어가 보고 잔고에서 사라집니다.</li>
 * </ul>
 *
 * <p>이 상황의 현실적 발생 경로는 다중 노드의 시계 편차가 월 경계에 걸리는 경우입니다.
 * 단일 시계에서는 대상 월이 항상 현재 월이므로 재현되지 않습니다.
 */
@DisplayName("회귀 테스트: 월별 원장 기간 무결성")
class LedgerPeriodIntegrityTest extends IntegrationTestSupport {

    @Autowired private LedgerPeriodResolver periodResolver;
    @Autowired private MonthlyLedgerResolver ledgerResolver;
    @Autowired private MonthlyAccountLedgerRepository ledgerRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private AccountApi accountApi;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TransactionTemplate tx;

    @AfterEach
    void tearDown() {
        jdbc.execute("TRUNCATE TABLE monthly_account_ledgers CASCADE");
        deleteTestAccounts();
    }

    private UUID seedAccountWithLedger(String month, String btcBalance) {
        UUID accountId = UUID.randomUUID();
        tx.execute(s -> {
            accountRepository.save(Account.open(accountId, "PERIOD", "KRW"));
            MonthlyAccountLedger ledger =
                    MonthlyAccountLedger.initialize(accountId, "BTC", AssetType.CRYPTO, month, "KRW");
            ledger.addBalance(Money.of(btcBalance, AssetType.CRYPTO, "BTC"), BigDecimal.ONE);
            ledgerRepository.save(ledger);
            return null;
        });
        return accountId;
    }

    private BigDecimal balanceOf(UUID accountId, String month) {
        var rows = jdbc.queryForList("SELECT balance FROM monthly_account_ledgers "
                + "WHERE account_id = ? AND asset_code = 'BTC' AND ledger_month = ?", accountId, month);
        return rows.isEmpty() ? null : (BigDecimal) rows.get(0).get("balance");
    }

    @Test
    @DisplayName("과거 월 원장을 초기화할 때 미래 월의 잔고를 끌어오지 않는다")
    void carry_forward_never_reads_from_the_future() {
        // 시계가 앞선 노드가 미래(2026-08) 원장을 먼저 만들어 둔 상황
        UUID accountId = seedAccountWithLedger("2026-08", "50");

        // 과거(2026-07) 원장을 초기화한다
        ledgerResolver.resolveOrInitializeLedger(accountId, "BTC", AssetType.CRYPTO, "2026-07");

        assertThat(balanceOf(accountId, "2026-07"))
                .as("미래(2026-08)의 잔고 50 이 과거 원장의 기초 잔고로 복사되면 안 된다")
                .isEqualByComparingTo("0");
        assertThat(balanceOf(accountId, "2026-08"))
                .as("미래 원장은 영향을 받지 않아야 한다")
                .isEqualByComparingTo("50");
    }

    @Test
    @DisplayName("정상적인 순방향 이월은 직전 월의 잔고를 그대로 가져온다")
    void forward_carry_forward_still_works() {
        UUID accountId = seedAccountWithLedger("2026-07", "12");

        ledgerResolver.resolveOrInitializeLedger(accountId, "BTC", AssetType.CRYPTO, "2026-08");

        assertThat(balanceOf(accountId, "2026-08"))
                .as("직전 월(2026-07)의 마감 잔고가 이월되어야 한다")
                .isEqualByComparingTo("12");

        var flag = jdbc.queryForObject("SELECT carried_forward FROM monthly_account_ledgers "
                + "WHERE account_id = ? AND ledger_month = '2026-08'", Boolean.class, accountId);
        assertThat(flag).isTrue();
    }

    @Test
    @DisplayName("여러 달을 건너뛴 이월도 가장 가까운 과거 월에서 가져온다")
    void carry_forward_picks_the_nearest_past_month() {
        UUID accountId = seedAccountWithLedger("2026-03", "1");
        tx.execute(s -> {
            MonthlyAccountLedger may =
                    MonthlyAccountLedger.initialize(accountId, "BTC", AssetType.CRYPTO, "2026-05", "KRW");
            may.addBalance(Money.of("9", AssetType.CRYPTO, "BTC"), BigDecimal.ONE);
            ledgerRepository.save(may);
            return null;
        });

        ledgerResolver.resolveOrInitializeLedger(accountId, "BTC", AssetType.CRYPTO, "2026-07");

        assertThat(balanceOf(accountId, "2026-07"))
                .as("2026-03(1)이 아니라 2026-05(9)에서 이월되어야 한다")
                .isEqualByComparingTo("9");
    }

    @Test
    @DisplayName("거래 시각이 최신 원장 월보다 과거면 최신 월에 기장한다")
    void write_is_redirected_to_the_month_the_read_path_sees() {
        UUID accountId = seedAccountWithLedger("2026-08", "50");

        // 시계가 정확한 노드가 2026-07 시각으로 거래를 시도하는 상황
        OffsetDateTime pastTrade = OffsetDateTime.of(2026, 7, 15, 12, 0, 0, 0, ZoneOffset.UTC);

        String effectiveMonth = periodResolver.resolveLedgerMonth(accountId, pastTrade);

        assertThat(effectiveMonth)
                .as("읽기 경로가 MAX(ledger_month)=2026-08 을 보므로 쓰기도 같은 월을 향해야 한다. "
                        + "2026-07 에 기장하면 거래가 보고 잔고에서 사라진다")
                .isEqualTo("2026-08");
    }

    @Test
    @DisplayName("거래 시각이 최신 원장 월과 같거나 미래면 거래 시각의 월을 그대로 쓴다")
    void normal_case_uses_the_month_of_the_trade() {
        UUID accountId = seedAccountWithLedger("2026-07", "5");

        OffsetDateTime sameMonth = OffsetDateTime.of(2026, 7, 20, 9, 0, 0, 0, ZoneOffset.UTC);
        OffsetDateTime nextMonth = OffsetDateTime.of(2026, 8, 2, 9, 0, 0, 0, ZoneOffset.UTC);

        assertThat(periodResolver.resolveLedgerMonth(accountId, sameMonth)).isEqualTo("2026-07");
        assertThat(periodResolver.resolveLedgerMonth(accountId, nextMonth)).isEqualTo("2026-08");
    }

    @Test
    @DisplayName("원장이 하나도 없으면 거래 시각의 월을 사용한다")
    void first_ever_ledger_uses_the_month_of_the_trade() {
        UUID accountId = UUID.randomUUID();
        accountRepository.saveAndFlush(Account.open(accountId, "FIRST", "KRW"));

        OffsetDateTime trade = OffsetDateTime.of(2026, 6, 10, 9, 0, 0, 0, ZoneOffset.UTC);
        assertThat(periodResolver.resolveLedgerMonth(accountId, trade)).isEqualTo("2026-06");
    }

    @Test
    @DisplayName("기장 월 결정은 계좌 단위여서 자산 원장과 법정화폐 원장이 같은 월에 놓인다")
    void effective_month_is_account_scoped() {
        UUID accountId = seedAccountWithLedger("2026-08", "50");
        // 법정화폐 원장은 과거 월에만 존재하는 비대칭 상황
        tx.execute(s -> {
            MonthlyAccountLedger krw =
                    MonthlyAccountLedger.initialize(accountId, "KRW", AssetType.FIAT, "2026-07", "KRW");
            krw.addBalance(Money.of("1000", AssetType.FIAT, "KRW"), BigDecimal.ONE);
            ledgerRepository.save(krw);
            return null;
        });

        OffsetDateTime pastTrade = OffsetDateTime.of(2026, 7, 15, 12, 0, 0, 0, ZoneOffset.UTC);
        String month = periodResolver.resolveLedgerMonth(accountId, pastTrade);

        // 자산별로 판단하면 BTC=2026-08, KRW=2026-07 로 갈려 이후 원장 조회가 실패한다.
        assertThat(month).isEqualTo("2026-08");

        ledgerResolver.resolveOrInitializeLedger(accountId, "BTC", AssetType.CRYPTO, month);
        ledgerResolver.resolveOrInitializeLedger(accountId, "KRW", AssetType.FIAT, month);

        assertThat(ledgerRepository.findByAccountIdAndAssetCodeAndLedgerMonth(accountId, "BTC", month)).isPresent();
        assertThat(ledgerRepository.findByAccountIdAndAssetCodeAndLedgerMonth(accountId, "KRW", month))
                .as("법정화폐 원장도 같은 월로 초기화되어야 한다")
                .isPresent();
    }

    @Test
    @DisplayName("보고 잔고는 항상 최신 월 원장을 따른다")
    void reported_balance_follows_the_latest_month() {
        UUID accountId = seedAccountWithLedger("2026-07", "10");
        tx.execute(s -> {
            MonthlyAccountLedger aug = MonthlyAccountLedger.carryForwardFrom(
                    ledgerRepository.findByAccountIdAndAssetCodeAndLedgerMonth(accountId, "BTC", "2026-07").orElseThrow(),
                    "2026-08");
            ledgerRepository.save(aug);
            return null;
        });

        assertThat(accountApi.getBalances(accountId))
                .singleElement()
                .satisfies(b -> assertThat(b.totalQuantity()).isEqualByComparingTo("10"));
    }
}
