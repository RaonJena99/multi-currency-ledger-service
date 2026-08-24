package com.github.raonjena99.multi_currency_ledger_service.regression;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
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
 * 실현 손익과 처분 금액의 <b>단위</b>가 맞는지 검증합니다.
 *
 * <p>이 결함군은 대차평균 검증으로 절대 잡히지 않습니다. 매도 엔트리의
 * {@code amount + realizedPnl} 이 대수적으로 {@code sellPrice × qty × rate} 로 상쇄되기 때문에,
 * 평균 단가의 단위를 틀리게 넣어도 차변과 대변은 <b>정확히</b> 일치합니다.
 * 그래서 개별 엔트리의 {@code amount} 와 {@code realized_pnl} 값 자체를 직접 검증해야 합니다.
 *
 * <pre>
 *   amount + pnl = cost×qty×rate + (sellPrice−cost)×qty×rate = sellPrice×qty×rate
 *                  ^^^^ cost 가 무엇이든 상쇄된다
 * </pre>
 */
@DisplayName("회귀 테스트: 실현 손익 단위 정합성")
class RealizedPnlUnitTest extends IntegrationTestSupport {

    @Autowired private LedgerService ledgerService;
    @Autowired private AccountRepository accountRepository;
    @Autowired private JdbcTemplate jdbc;

    @AfterEach
    void tearDown() {
        jdbc.execute("TRUNCATE TABLE transaction_entries, transactions CASCADE");
        deleteTestAccounts();
    }

    private UUID account(String name) {
        UUID id = UUID.randomUUID();
        accountRepository.saveAndFlush(Account.open(id, name, "KRW"));
        return id;
    }

    private List<Map<String, Object>> entries(UUID tradeId) {
        return jdbc.queryForList("SELECT entry_type, asset_code, unit_price, amount, realized_pnl "
                + "FROM transaction_entries WHERE transaction_id = ? ORDER BY id", tradeId);
    }

    private Map<String, Object> entryOf(UUID tradeId, String assetCode) {
        return entries(tradeId).stream()
                .filter(r -> assetCode.equals(r.get("asset_code")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("entry not found: " + assetCode));
    }

    private void assertBalanced(UUID tradeId) {
        BigDecimal debit = BigDecimal.ZERO;
        BigDecimal credit = BigDecimal.ZERO;
        for (var r : entries(tradeId)) {
            BigDecimal amount = (BigDecimal) r.get("amount");
            BigDecimal pnl = (BigDecimal) r.get("realized_pnl");
            if ("DEBIT".equals(r.get("entry_type"))) debit = debit.add(amount);
            else credit = credit.add(amount);
            if (pnl != null) credit = credit.add(pnl);
        }
        assertThat(debit).isEqualByComparingTo(credit);
    }

    @Test
    @DisplayName("이종 통화 매도의 처분 금액과 실현 손익이 기준 통화 단위로 정확히 기록된다")
    void cross_currency_sell_records_correct_amount_and_pnl() {
        UUID accountId = account("SELL");
        UUID tradeId = UUID.randomUUID();

        // 1 BTC 매도. 매도 단가 120 USD, 환율 1300 KRW/USD, 평균 매입 단가 130,000 KRW
        //   처분 금액(원가) = 130,000 KRW
        //   실현 손익 = 120 × 1300 − 130,000 = 26,000 KRW
        ledgerService.recordDoubleEntry(new LedgerRecordingCommand(
                tradeId, accountId, "BTC", AssetType.CRYPTO, "USD", "KRW", "SELL",
                Money.of("1", AssetType.CRYPTO, "BTC"), new BigDecimal("120"),
                BigDecimal.ONE, new BigDecimal("1300"), new BigDecimal("130000"),
                false, OffsetDateTime.now()));

        var btc = entryOf(tradeId, "BTC");

        assertThat((BigDecimal) btc.get("amount"))
                .as("처분 금액은 기준 통화 원가여야 한다. 환산 없이 빼면 130,000×1300 = 1억 6900만이 된다")
                .isEqualByComparingTo("130000");
        assertThat((BigDecimal) btc.get("realized_pnl"))
                .as("실현 손익은 +26,000 KRW 여야 한다. 단위가 틀리면 -168,844,000 이 된다")
                .isEqualByComparingTo("26000");

        // 대차는 어느 쪽이든 맞으므로, 이 검증만으로는 결함을 잡을 수 없다.
        assertBalanced(tradeId);
    }

    @Test
    @DisplayName("환율이 1이면 실현 손익이 결제 통화 계산과 동일하다")
    void same_currency_sell_pnl_matches_direct_calculation() {
        UUID accountId = account("SELL_KRW");
        UUID tradeId = UUID.randomUUID();

        // 2 BTC, 매도 단가 1000 KRW, 평균 단가 800 KRW → 실현이익 400 KRW
        ledgerService.recordDoubleEntry(new LedgerRecordingCommand(
                tradeId, accountId, "BTC", AssetType.CRYPTO, "KRW", "KRW", "SELL",
                Money.of("2", AssetType.CRYPTO, "BTC"), new BigDecimal("1000"),
                BigDecimal.ONE, BigDecimal.ONE, new BigDecimal("800"),
                false, OffsetDateTime.now()));

        var btc = entryOf(tradeId, "BTC");
        assertThat((BigDecimal) btc.get("amount")).isEqualByComparingTo("1600");
        assertThat((BigDecimal) btc.get("realized_pnl")).isEqualByComparingTo("400");
        assertBalanced(tradeId);
    }

    @Test
    @DisplayName("손실 매도의 실현 손익은 음수로 기록된다")
    void loss_making_sell_records_negative_pnl() {
        UUID accountId = account("SELL_LOSS");
        UUID tradeId = UUID.randomUUID();

        // 1 BTC, 매도 100 USD × 1300 = 130,000 KRW, 평균 단가 150,000 KRW → -20,000 KRW
        ledgerService.recordDoubleEntry(new LedgerRecordingCommand(
                tradeId, accountId, "BTC", AssetType.CRYPTO, "USD", "KRW", "SELL",
                Money.of("1", AssetType.CRYPTO, "BTC"), new BigDecimal("100"),
                BigDecimal.ONE, new BigDecimal("1300"), new BigDecimal("150000"),
                false, OffsetDateTime.now()));

        var btc = entryOf(tradeId, "BTC");
        assertThat((BigDecimal) btc.get("amount")).isEqualByComparingTo("150000");
        assertThat((BigDecimal) btc.get("realized_pnl")).isEqualByComparingTo("-20000");
        assertBalanced(tradeId);
    }

    @Test
    @DisplayName("외화 매수의 법정화폐 대변에는 실현 손익이 발생하지 않는다")
    void foreign_currency_buy_has_no_realized_pnl() {
        UUID accountId = account("BUY");
        UUID tradeId = UUID.randomUUID();

        ledgerService.recordDoubleEntry(new LedgerRecordingCommand(
                tradeId, accountId, "BTC", AssetType.CRYPTO, "USD", "KRW", "BUY",
                Money.of("2", AssetType.CRYPTO, "BTC"), new BigDecimal("10"),
                BigDecimal.ONE, new BigDecimal("1300"), null,
                false, OffsetDateTime.now()));

        assertThat(entries(tradeId))
                .as("매수에는 실현 손익이 없다. 센티넬 단위를 틀리면 (1×rate−1)×qty 만큼 가짜 손익이 생긴다")
                .allSatisfy(r -> assertThat((BigDecimal) r.get("realized_pnl")).isEqualByComparingTo("0"));
        assertBalanced(tradeId);
    }

    @Test
    @DisplayName("수수료 보정에는 실현 손익이 발생하지 않는다")
    void fee_adjustment_has_no_realized_pnl() {
        UUID accountId = account("FEE_GAIN");
        UUID tradeId = UUID.randomUUID();

        ledgerService.recordDoubleEntry(new LedgerRecordingCommand(
                tradeId, accountId, "USD", AssetType.FIAT, "USD", "KRW", "FEE_ADJUSTMENT",
                Money.of("50", AssetType.FIAT, "USD"), BigDecimal.ONE,
                BigDecimal.ONE, new BigDecimal("1300"), BigDecimal.ZERO,
                false, OffsetDateTime.now()));

        assertThat(entries(tradeId))
                .isNotEmpty()
                .allSatisfy(r -> assertThat((BigDecimal) r.get("realized_pnl"))
                        .as("수수료 보정을 실현 손익으로 기록하면 손익계산서가 오염된다")
                        .isEqualByComparingTo("0"));
        assertBalanced(tradeId);
    }

    @Test
    @DisplayName("수수료 보정(초과 지불)에도 실현 손익이 없다")
    void fee_adjustment_loss_has_no_realized_pnl() {
        UUID accountId = account("FEE_LOSS");
        UUID tradeId = UUID.randomUUID();

        ledgerService.recordDoubleEntry(new LedgerRecordingCommand(
                tradeId, accountId, "USD", AssetType.FIAT, "USD", "KRW", "FEE_ADJUSTMENT",
                Money.of("-50", AssetType.FIAT, "USD"), BigDecimal.ONE,
                BigDecimal.ONE, new BigDecimal("1300"), BigDecimal.ZERO,
                false, OffsetDateTime.now()));

        assertThat(entries(tradeId))
                .isNotEmpty()
                .allSatisfy(r -> assertThat((BigDecimal) r.get("realized_pnl")).isEqualByComparingTo("0"));
        assertBalanced(tradeId);
    }

    @Test
    @DisplayName("평균 단가가 없는 수수료 차감에도 실현 손익이 없다")
    void fee_deduction_without_average_cost_has_no_realized_pnl() {
        UUID accountId = account("FEE_DED");
        UUID tradeId = UUID.randomUUID();

        ledgerService.recordDoubleEntry(new LedgerRecordingCommand(
                tradeId, accountId, "BTC", AssetType.CRYPTO, "USD", "KRW", "FEE_DEDUCTION",
                Money.of("0.1", AssetType.CRYPTO, "BTC"), new BigDecimal("500"),
                BigDecimal.ONE, new BigDecimal("1300"), null,
                false, OffsetDateTime.now()));

        assertThat(entries(tradeId))
                .isNotEmpty()
                .allSatisfy(r -> assertThat((BigDecimal) r.get("realized_pnl"))
                        .as("폴백 평균 단가가 결제 통화면 (unitPrice×rate − unitPrice)×qty 의 가짜 손익이 생긴다")
                        .isEqualByComparingTo("0"));
        assertBalanced(tradeId);
    }
}
