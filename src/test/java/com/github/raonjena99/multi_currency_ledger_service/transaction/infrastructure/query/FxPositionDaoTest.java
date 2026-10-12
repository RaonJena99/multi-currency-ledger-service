package com.github.raonjena99.multi_currency_ledger_service.transaction.infrastructure.query;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.github.raonjena99.multi_currency_ledger_service.IntegrationTestSupport;
import com.github.raonjena99.multi_currency_ledger_service.account.domain.Account;
import com.github.raonjena99.multi_currency_ledger_service.account.infrastructure.AccountRepository;
import com.github.raonjena99.multi_currency_ledger_service.common.domain.Money;
import com.github.raonjena99.multi_currency_ledger_service.common.model.AssetType;
import com.github.raonjena99.multi_currency_ledger_service.transaction.application.LedgerService;
import com.github.raonjena99.multi_currency_ledger_service.transaction.application.command.FxConversionDetail;
import com.github.raonjena99.multi_currency_ledger_service.transaction.application.command.LedgerRecordingCommand;
import com.github.raonjena99.multi_currency_ledger_service.transaction.infrastructure.query.FxPositionDao.PositionRow;
import com.github.raonjena99.multi_currency_ledger_service.transaction.infrastructure.query.FxPositionDao.SpreadRevenueRow;

/**
 * 회사 외화 포지션 집계를 실제 DB 로 검증합니다.
 *
 * <p>환전은 회사 FX 계정({@code SYSTEM_FX})에 통화별 분개를 남깁니다. 이 집계는 그 분개를 통화와 기준 통화별로 합쳐 순수량과
 * 장부가를 구하고, 스프레드 수익({@code SYSTEM_FEE} 의 {@code SYSTEM_FX_SPREAD})을 기준 통화별로 합칩니다.
 */
@DisplayName("회사 외화 포지션 집계")
class FxPositionDaoTest extends IntegrationTestSupport {

    private static final OffsetDateTime TRADED_AT = OffsetDateTime.now(ZoneOffset.UTC);
    private static final UUID SYSTEM_FX = new UUID(0, 0);

    @Autowired private FxPositionDao dao;
    @Autowired private LedgerService ledgerService;
    @Autowired private AccountRepository accountRepository;
    @Autowired private JdbcTemplate jdbc;

    @AfterEach
    void tearDown() {
        jdbc.execute("TRUNCATE TABLE transaction_entries, transactions CASCADE");
        deleteTestAccounts();
    }

    private UUID account(String baseCurrency) {
        UUID id = UUID.randomUUID();
        accountRepository.saveAndFlush(Account.open(id, "FX_POS_USER", baseCurrency));
        return id;
    }

    /** 환전 하나를 원장에 기록한다. 외화 금액은 {@code foreignAmount}, 기준 통화 금액은 {@code baseAmount}. */
    private void convert(UUID accountId, String direction, String base, String foreign, String foreignAmount,
                         String baseAmount, String marketValue, String averageCost) {
        BigDecimal baseValue = new BigDecimal(baseAmount);
        ledgerService.recordDoubleEntry(new LedgerRecordingCommand(UUID.randomUUID(), accountId, foreign,
                AssetType.FIAT, base, base, "FX_CONVERSION", Money.of(foreignAmount, AssetType.FIAT, foreign),
                baseValue.divide(new BigDecimal(foreignAmount), 18, RoundingMode.HALF_EVEN), BigDecimal.ONE,
                BigDecimal.ONE, averageCost == null ? null : new BigDecimal(averageCost), false, TRADED_AT,
                new FxConversionDetail(direction, baseValue, new BigDecimal(marketValue))));
    }

    private Map<String, PositionRow> byKey(List<PositionRow> rows) {
        return rows.stream().collect(Collectors.toMap(r -> r.baseCurrency() + "/" + r.currency(), r -> r));
    }

    @Test
    @DisplayName("고객이 달러를 사면 회사는 달러를 판 포지션(음수)과 원화를 받은 포지션(양수)을 갖는다")
    void customerBuyingUsdLeavesTheCompanyShortUsd() {
        convert(account("KRW"), "BUY_FOREIGN", "KRW", "USD", "1000", "1322750", "1300000", null);

        Map<String, PositionRow> positions = byKey(dao.findPositions());

        assertThat(positions).containsOnlyKeys("KRW/USD", "KRW/KRW");
        PositionRow usd = positions.get("KRW/USD");
        assertThat(usd.netQuantity()).as("회사가 내준 달러").isEqualByComparingTo("-1000");
        assertThat(usd.bookValue()).as("내준 달러의 시세 가치(원화)").isEqualByComparingTo("-1300000");
        PositionRow krw = positions.get("KRW/KRW");
        assertThat(krw.netQuantity()).isEqualByComparingTo("1322750");
        assertThat(krw.bookValue()).isEqualByComparingTo("1322750");
    }

    @Test
    @DisplayName("고객이 달러를 팔면 같은 포지션이 줄어든다 (왕복하면 달러는 0, 장부가에 손익이 남는다)")
    void roundTripLeavesAFlatPositionWithABookValue() {
        UUID accountId = account("KRW");
        convert(accountId, "BUY_FOREIGN", "KRW", "USD", "1000", "1322750", "1300000", null);
        // 시세가 1,400 으로 오른 뒤 되판다: 고객은 1,000 × 1,400 × 0.9825 = 1,375,500 원을 받고, 회사는 달러를 1,400,000 에 받는다.
        convert(accountId, "SELL_FOREIGN", "KRW", "USD", "1000", "1375500", "1400000", "1322.75");

        Map<String, PositionRow> positions = byKey(dao.findPositions());

        PositionRow usd = positions.get("KRW/USD");
        assertThat(usd.netQuantity()).isEqualByComparingTo("0");
        assertThat(usd.bookValue()).as("1,300,000 에 팔고 1,400,000 에 되산 값").isEqualByComparingTo("100000");
        assertThat(positions.get("KRW/KRW").netQuantity()).as("회사가 고객에게 순지급한 원화").isEqualByComparingTo("-52750");
    }

    @Test
    @DisplayName("고객 계좌의 기준 통화가 다르면 기준 통화별로 따로 집계한다")
    void groupsByTheCustomerBaseCurrency() {
        convert(account("KRW"), "BUY_FOREIGN", "KRW", "USD", "1000", "1322750", "1300000", null);
        // 달러 기준 계좌가 원화를 산다: 1,000.00 USD → KRW 1,310,319 (시세 가치 982.73)
        convert(account("USD"), "BUY_FOREIGN", "USD", "KRW", "1310319", "1000.00", "982.73", null);

        Map<String, PositionRow> positions = byKey(dao.findPositions());

        assertThat(positions).containsOnlyKeys("KRW/USD", "KRW/KRW", "USD/KRW", "USD/USD");
        assertThat(positions.get("USD/KRW").netQuantity()).isEqualByComparingTo("-1310319");
        assertThat(positions.get("USD/KRW").bookValue()).isEqualByComparingTo("-982.73");
        assertThat(positions.get("USD/USD").netQuantity()).isEqualByComparingTo("1000.00");
    }

    @Test
    @DisplayName("반올림 잔차를 흘려보낸 시스템 환차익·환차손 분개는 포지션이 아니다")
    void roundingResidualEntriesAreNotPositions() {
        UUID accountId = account("KRW");
        convert(accountId, "BUY_FOREIGN", "KRW", "USD", "1000", "1322750", "1300000", null);
        UUID tradeId = UUID.randomUUID();
        jdbc.update("INSERT INTO transactions (id, transaction_type, description, transacted_at) "
                + "VALUES (?, 'FX_CONVERSION', 'plug', now())", tradeId);
        jdbc.update("INSERT INTO transaction_entries (id, transaction_id, account_id, entry_type, asset_code, quantity, "
                + "quantity_asset_type, quantity_currency, unit_price, amount, amount_asset_type, amount_currency, "
                + "realized_pnl, realized_pnl_asset_type, realized_pnl_currency, exchange_rate) "
                + "VALUES (nextval('transaction_entry_seq'), ?, ?, 'CREDIT', 'SYSTEM_FX_GAIN', 3, 'FIAT', 'KRW', 1, 3, "
                + "'FIAT', 'KRW', 0, 'FIAT', 'KRW', 1)", tradeId, SYSTEM_FX);

        assertThat(dao.findPositions()).extracting(PositionRow::currency).doesNotContain("SYSTEM_FX_GAIN");
    }

    @Test
    @DisplayName("고객 계정의 분개는 회사 포지션에 들어가지 않는다")
    void customerEntriesAreNotCompanyPositions() {
        convert(account("KRW"), "BUY_FOREIGN", "KRW", "USD", "1000", "1322750", "1300000", null);

        // 고객 쪽 분개(USD 차변 1,000)가 더해졌다면 회사 USD 순수량이 −1,000 이 아니라 0 이 된다.
        assertThat(byKey(dao.findPositions()).get("KRW/USD").netQuantity()).isEqualByComparingTo("-1000");
    }

    @Test
    @DisplayName("스프레드 수익은 기준 통화별 합이다")
    void spreadRevenueIsSummedPerBaseCurrency() {
        UUID krwAccount = account("KRW");
        convert(krwAccount, "BUY_FOREIGN", "KRW", "USD", "1000", "1322750", "1300000", null);
        convert(krwAccount, "SELL_FOREIGN", "KRW", "USD", "1000", "1277250", "1300000", "1322.75");
        convert(account("USD"), "BUY_FOREIGN", "USD", "KRW", "1310319", "1000.00", "982.73", null);

        Map<String, BigDecimal> revenue = dao.findSpreadRevenue().stream()
                .collect(Collectors.toMap(SpreadRevenueRow::baseCurrency, SpreadRevenueRow::revenue));

        assertThat(revenue).containsOnlyKeys("KRW", "USD");
        assertThat(revenue.get("KRW")).isEqualByComparingTo("45500");
        assertThat(revenue.get("USD")).isEqualByComparingTo("17.27");
    }

    @Test
    @DisplayName("기준 통화별 장부가의 합은 그 기준 통화의 스프레드 수익과 같다")
    void bookValuesSumToTheSpreadRevenue() {
        UUID krwAccount = account("KRW");
        convert(krwAccount, "BUY_FOREIGN", "KRW", "USD", "1000", "1322750", "1300000", null);
        convert(krwAccount, "BUY_FOREIGN", "KRW", "USD", "400", "529100", "520000", null);
        convert(krwAccount, "SELL_FOREIGN", "KRW", "USD", "700", "894075", "910000", "1322.75");
        convert(account("USD"), "BUY_FOREIGN", "USD", "KRW", "1310319", "1000.00", "982.73", null);

        Map<String, BigDecimal> bookTotal = dao.findPositions().stream().collect(
                Collectors.groupingBy(PositionRow::baseCurrency,
                        Collectors.reducing(BigDecimal.ZERO, PositionRow::bookValue, BigDecimal::add)));
        Map<String, BigDecimal> revenue = dao.findSpreadRevenue().stream()
                .collect(Collectors.toMap(SpreadRevenueRow::baseCurrency, SpreadRevenueRow::revenue));

        // 환전 한 건에서 회사 FX 계정의 차변 − 대변이 곧 그 건의 스프레드 수익이다.
        assertThat(bookTotal.keySet()).isEqualTo(revenue.keySet());
        bookTotal.forEach((base, total) -> assertThat(total).as("%s 장부가 합계", base)
                .isEqualByComparingTo(revenue.get(base)));
    }

    @Test
    @DisplayName("환전이 없으면 비어 있다")
    void emptyWhenThereAreNoConversions() {
        assertThat(dao.findPositions()).isEmpty();
        assertThat(dao.findSpreadRevenue()).isEmpty();
    }
}
