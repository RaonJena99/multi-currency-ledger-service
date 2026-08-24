package com.github.raonjena99.multi_currency_ledger_service.regression;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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
import com.github.raonjena99.multi_currency_ledger_service.transaction.application.command.LedgerRecordingCommand;

/**
 * 외화 거래의 원장 기록 계약을 고정합니다.
 *
 * <p>결제 통화와 기준 통화가 다르면 두 종류의 반올림이 겹칩니다.
 * <ul>
 *   <li>결제 통화 스케일로의 정규화 (USD → 소수 2자리)</li>
 *   <li>기준 통화 스케일로의 환산 저장 (KRW → 소수 0자리)</li>
 * </ul>
 * 앞의 오차는 <b>환율을 타고 증폭</b>됩니다. USD 최소 단위 0.01 이 환율 1300 을 만나면 기준 통화에서
 * 13 KRW 가 됩니다. 허용 잔차를 "엔트리 수 × 기준 통화 최소 단위"(2 KRW)로 두면 정상적인 외화 거래가
 * {@code DoubleEntryImbalanceException} 으로 전부 실패합니다.
 *
 * <p>그리고 원장이 기록하는 결제 금액은 잔고가 실제로 움직인 금액과 <b>같은 반올림 방향</b>이어야
 * 합니다. 방향이 다르면 복식부기 기록이 잔고와 어긋납니다.
 */
@DisplayName("회귀 테스트: 외화 거래 원장 기록")
class ForeignCurrencyLedgerTest extends IntegrationTestSupport {

    @Autowired private LedgerService ledgerService;
    @Autowired private AccountRepository accountRepository;
    @Autowired private JdbcTemplate jdbc;

    @AfterEach
    void tearDown() {
        jdbc.execute("TRUNCATE TABLE transaction_entries, transactions CASCADE");
        deleteTestAccounts();
    }

    private UUID account() {
        UUID id = UUID.randomUUID();
        accountRepository.saveAndFlush(Account.open(id, "FX", "KRW"));
        return id;
    }

    private LedgerRecordingCommand command(UUID tradeId, UUID accountId, String tradeType,
                                           String quantity, AssetType assetType, String assetCode,
                                           String unitPrice, String fiatToBaseRate, String averageCost) {
        return new LedgerRecordingCommand(
                tradeId, accountId, assetCode, assetType,
                "USD", "KRW", tradeType,
                Money.of(quantity, assetType, assetCode),
                new BigDecimal(unitPrice),
                BigDecimal.ONE,
                new BigDecimal(fiatToBaseRate),
                averageCost == null ? null : new BigDecimal(averageCost),
                false,
                OffsetDateTime.now());
    }

    private List<Map<String, Object>> entriesOf(UUID tradeId) {
        return jdbc.queryForList("SELECT entry_type, asset_code, amount, realized_pnl "
                + "FROM transaction_entries WHERE transaction_id = ? ORDER BY id", tradeId);
    }

    private BigDecimal sum(List<Map<String, Object>> rows, String type) {
        return rows.stream()
                .filter(r -> type.equals(r.get("entry_type")))
                .map(r -> (BigDecimal) r.get("amount"))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal pnl(List<Map<String, Object>> rows) {
        return rows.stream()
                .map(r -> (BigDecimal) r.get("realized_pnl"))
                .filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Test
    @DisplayName("환율 증폭 반올림 오차가 있는 외화 매수도 원장에 기록된다")
    void foreign_currency_buy_is_recorded_despite_rate_amplified_rounding() {
        UUID accountId = account();
        UUID tradeId = UUID.randomUUID();

        // 0.5 BTC x 1.01 USD = 0.505 USD → USD 스케일 정규화에서 오차 발생, 환율 1300 으로 증폭
        ledgerService.recordDoubleEntry(command(tradeId, accountId, "BUY",
                "0.5", AssetType.CRYPTO, "BTC", "1.01", "1300", null));

        var entries = entriesOf(tradeId);
        assertThat(entries).as("원장이 기록되어야 한다").hasSizeGreaterThanOrEqualTo(2);
        assertThat(sum(entries, "DEBIT"))
                .as("플러그 엔트리 추가 후 대차가 정확히 일치해야 한다")
                .isEqualByComparingTo(sum(entries, "CREDIT").add(pnl(entries)));
    }

    @Test
    @DisplayName("환율이 매우 큰 통화쌍에서도 기록에 성공한다")
    void works_for_high_rate_currency_pairs() {
        UUID accountId = account();
        UUID tradeId = UUID.randomUUID();

        // 환율 25000 (예: USD → VND 급) 이면 USD 1센트가 250 단위로 증폭된다
        ledgerService.recordDoubleEntry(command(tradeId, accountId, "BUY",
                "0.333", AssetType.CRYPTO, "BTC", "3.77", "25000", null));

        var entries = entriesOf(tradeId);
        assertThat(entries).isNotEmpty();
        assertThat(sum(entries, "DEBIT")).isEqualByComparingTo(sum(entries, "CREDIT").add(pnl(entries)));
    }

    @Test
    @DisplayName("외화 매도도 실현손익을 포함해 대차가 맞는다")
    void foreign_currency_sell_balances_with_realized_pnl() {
        UUID accountId = account();
        UUID tradeId = UUID.randomUUID();

        ledgerService.recordDoubleEntry(command(tradeId, accountId, "SELL",
                "0.5", AssetType.CRYPTO, "BTC", "1.01", "1300", "0.90"));

        var entries = entriesOf(tradeId);
        assertThat(entries).isNotEmpty();
        assertThat(sum(entries, "DEBIT")).isEqualByComparingTo(sum(entries, "CREDIT").add(pnl(entries)));
    }

    @Test
    @DisplayName("매수 시 원장이 기록하는 결제 금액은 잔고가 차감한 금액과 같다 (올림)")
    void buy_records_the_same_fiat_amount_the_balance_paid() {
        BigDecimal raw = new BigDecimal("1.01").multiply(new BigDecimal("0.5"));   // 0.505 USD

        // AccountTradeService 가 잔고에서 차감하는 금액 (고객이 지불 → UP)
        Money balanceMovement = Money.of(raw, AssetType.FIAT, "USD", RoundingMode.UP);
        assertThat(balanceMovement.getAmount()).isEqualByComparingTo("0.51");

        UUID accountId = account();
        UUID tradeId = UUID.randomUUID();
        ledgerService.recordDoubleEntry(command(tradeId, accountId, "BUY",
                "0.5", AssetType.CRYPTO, "BTC", "1.01", "1300", null));

        // 플러그 엔트리가 섞이지 않도록 결제 통화 엔트리의 quantity 를 직접 확인한다.
        // quantity 는 기준 통화로 환산되기 전의 결제 통화 금액이다.
        BigDecimal recordedFiat = jdbc.queryForObject(
                "SELECT quantity FROM transaction_entries WHERE transaction_id = ? AND asset_code = 'USD'",
                BigDecimal.class, tradeId);

        assertThat(recordedFiat)
                .as("원장이 HALF_EVEN 을 쓰면 0.50 을 기록해 잔고가 차감한 0.51 과 어긋난다")
                .isEqualByComparingTo(balanceMovement.getAmount());
    }

    @Test
    @DisplayName("매도 시 원장이 기록하는 수취 금액은 잔고가 증가한 금액과 같다 (내림)")
    void sell_records_the_same_fiat_amount_the_balance_received() {
        BigDecimal raw = new BigDecimal("1.01").multiply(new BigDecimal("0.5"));   // 0.505 USD

        // 고객이 수취 → DOWN
        Money balanceMovement = Money.of(raw, AssetType.FIAT, "USD", RoundingMode.DOWN);
        assertThat(balanceMovement.getAmount()).isEqualByComparingTo("0.50");

        UUID accountId = account();
        UUID tradeId = UUID.randomUUID();
        ledgerService.recordDoubleEntry(command(tradeId, accountId, "SELL",
                "0.5", AssetType.CRYPTO, "BTC", "1.01", "1300", "0.90"));

        BigDecimal recordedFiat = jdbc.queryForObject(
                "SELECT quantity FROM transaction_entries WHERE transaction_id = ? AND asset_code = 'USD'",
                BigDecimal.class, tradeId);

        assertThat(recordedFiat)
                .as("수취 금액을 올림으로 기록하면 원장이 잔고보다 많은 금액을 기록한다")
                .isEqualByComparingTo(balanceMovement.getAmount());
    }

    @Test
    @DisplayName("동일 통화 거래는 잔차 없이 정확히 대차가 맞는다")
    void same_currency_trade_has_no_residual() {
        UUID accountId = UUID.randomUUID();
        accountRepository.saveAndFlush(Account.open(accountId, "KRW_ONLY", "KRW"));
        UUID tradeId = UUID.randomUUID();

        ledgerService.recordDoubleEntry(new LedgerRecordingCommand(
                tradeId, accountId, "BTC", AssetType.CRYPTO,
                "KRW", "KRW", "BUY",
                Money.of("2", AssetType.CRYPTO, "BTC"),
                new BigDecimal("1000"), BigDecimal.ONE, BigDecimal.ONE, null, false, OffsetDateTime.now()));

        var entries = entriesOf(tradeId);
        assertThat(entries)
                .as("동일 통화면 플러그 엔트리가 필요 없다")
                .hasSize(2);
        assertThat(sum(entries, "DEBIT")).isEqualByComparingTo("2000");
        assertThat(sum(entries, "CREDIT")).isEqualByComparingTo("2000");
    }

    @Test
    @DisplayName("플러그 금액은 반올림 수준을 넘지 않는다")
    void plug_stays_at_rounding_scale() {
        UUID accountId = account();
        UUID tradeId = UUID.randomUUID();

        ledgerService.recordDoubleEntry(command(tradeId, accountId, "BUY",
                "0.5", AssetType.CRYPTO, "BTC", "1.01", "1300", null));

        BigDecimal plug = entriesOf(tradeId).stream()
                .filter(r -> String.valueOf(r.get("asset_code")).startsWith("SYSTEM_FX"))
                .map(r -> ((BigDecimal) r.get("amount")).abs())
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // USD 최소단위(0.01) x 환율(1300) = 13 KRW 가 이론적 상한이다.
        assertThat(plug)
                .as("플러그가 반올림으로 설명 가능한 범위를 넘으면 계산 오류를 숨기는 것이다")
                .isLessThanOrEqualTo(new BigDecimal("13"));
    }
}
