package com.github.raonjena99.multi_currency_ledger_service.transaction.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.github.raonjena99.multi_currency_ledger_service.common.domain.Money;
import com.github.raonjena99.multi_currency_ledger_service.common.exception.DoubleEntryImbalanceException;
import com.github.raonjena99.multi_currency_ledger_service.common.model.AssetType;
import com.github.raonjena99.multi_currency_ledger_service.common.model.EntryType;
import com.github.raonjena99.multi_currency_ledger_service.transaction.application.command.LedgerRecordingCommand;
import com.github.raonjena99.multi_currency_ledger_service.transaction.domain.Transaction;
import com.github.raonjena99.multi_currency_ledger_service.transaction.infrastructure.TransactionRepository;

@ExtendWith(MockitoExtension.class)
@DisplayName("단위 테스트: LedgerService 복식부기 분개")
class LedgerServiceTest {

    private static final OffsetDateTime TRADED_AT = OffsetDateTime.parse("2026-07-15T10:00:00Z");

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private com.github.raonjena99.multi_currency_ledger_service.account.AccountApi accountApi;

    private LedgerService ledgerService;

    @org.junit.jupiter.api.BeforeEach
    void setUpService() {
        // LedgerService 는 플러그 규모를 지표로 노출하므로 MeterRegistry 를 주입받는다.
        // FEE_ADJUSTMENT 가 고객 잔고에도 반영되도록 AccountApi 를 함께 주입받는다.
        ledgerService = new LedgerService(transactionRepository, accountApi,
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
    }

    /** 커맨드 빌더. 테스트마다 필요한 값만 바꿔 쓴다. */
    private LedgerRecordingCommand cmd(UUID tradeId, String tradeType, String fiatCode, String baseCurrency,
                                      Money quantity, BigDecimal unitPrice, BigDecimal fiatToBaseRate,
                                      BigDecimal averageCost) {
        return new LedgerRecordingCommand(
                tradeId, UUID.randomUUID(), "BTC", AssetType.CRYPTO, fiatCode, baseCurrency, tradeType,
                quantity, unitPrice, BigDecimal.ONE, fiatToBaseRate, averageCost, false, TRADED_AT);
    }

    private Transaction capture() {
        ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository).saveAndFlush(captor.capture());
        return captor.getValue();
    }

    private BigDecimal debitTotal(Transaction tx) {
        return tx.getEntries().stream()
                .filter(e -> e.getEntryType() == EntryType.DEBIT)
                .map(e -> e.getAmount().getAmount())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * 대변 합계. 실현 손익은 고객 계정의 별도 분개(REALIZED_PNL_*)로 기록되므로 컬럼 값을 따로 더하지 않는다.
     */
    private BigDecimal creditTotal(Transaction tx) {
        return tx.getEntries().stream()
                .filter(e -> e.getEntryType() == EntryType.CREDIT)
                .map(e -> e.getAmount().getAmount())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** 실현 손익 분개(고객 계정). 손익이 없으면 비어 있다. */
    private java.util.List<com.github.raonjena99.multi_currency_ledger_service.transaction.domain.TransactionEntry>
            pnlEntries(Transaction tx) {
        return tx.getEntries().stream().filter(e -> e.getAssetCode().startsWith("REALIZED_PNL_")).toList();
    }

    @Test
    @DisplayName("이미 기록된 거래는 중복 기록하지 않는다")
    void ignores_duplicate() {
        UUID tradeId = UUID.randomUUID();
        when(transactionRepository.existsById(tradeId)).thenReturn(true);

        ledgerService.recordDoubleEntry(cmd(tradeId, "BUY", "USD", "USD",
                Money.of("1", AssetType.CRYPTO, "BTC"), new BigDecimal("1000"), BigDecimal.ONE, null));

        verify(transactionRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("커맨드에 실린 환율을 사용하고, 원장 기록 시점에 환율을 재조회하지 않는다")
    void uses_rate_from_command_without_refetching() {
        UUID tradeId = UUID.randomUUID();
        when(transactionRepository.existsById(tradeId)).thenReturn(false);

        BigDecimal appliedRate = new BigDecimal("1300");

        // 결제 통화(USD)와 기준 통화(KRW)가 다르지만, LedgerService 는 환율 제공자를 의존하지 않는다.
        // 의존성 자체가 없으므로 재조회가 구조적으로 불가능하다.
        ledgerService.recordDoubleEntry(cmd(tradeId, "BUY", "USD", "KRW",
                Money.of("2", AssetType.CRYPTO, "BTC"), new BigDecimal("10"), appliedRate, null));

        Transaction tx = capture();

        assertThat(tx.getEntries())
                .as("모든 엔트리가 커맨드의 환율을 그대로 사용해야 한다")
                .allSatisfy(e -> assertThat(e.getExchangeRate()).isEqualByComparingTo(appliedRate));

        // 차변: 2 BTC × 10 USD × 1300 = 26000 KRW
        assertThat(debitTotal(tx)).isEqualByComparingTo("26000");
        assertThat(creditTotal(tx)).isEqualByComparingTo(debitTotal(tx));
    }

    @Test
    @DisplayName("원장의 거래 시각은 소비 시각이 아니라 커맨드의 거래 시각을 따른다")
    void uses_transacted_at_from_command() {
        UUID tradeId = UUID.randomUUID();
        when(transactionRepository.existsById(tradeId)).thenReturn(false);

        ledgerService.recordDoubleEntry(cmd(tradeId, "BUY", "KRW", "KRW",
                Money.of("1", AssetType.CRYPTO, "BTC"), new BigDecimal("1000"), BigDecimal.ONE, null));

        assertThat(capture().getTransactedAt()).isEqualTo(TRADED_AT);
    }

    @Test
    @DisplayName("매수는 자산 차변과 법정화폐 대변으로 균형을 맞춘다")
    void buy_balances() {
        UUID tradeId = UUID.randomUUID();
        when(transactionRepository.existsById(tradeId)).thenReturn(false);

        ledgerService.recordDoubleEntry(cmd(tradeId, "BUY", "KRW", "KRW",
                Money.of("3", AssetType.CRYPTO, "BTC"), new BigDecimal("1000"), BigDecimal.ONE, null));

        Transaction tx = capture();
        assertThat(debitTotal(tx)).isEqualByComparingTo("3000");
        assertThat(creditTotal(tx)).isEqualByComparingTo("3000");
    }

    @Test
    @DisplayName("매도는 실현 손익을 포함해 균형을 맞춘다")
    void sell_balances_with_realized_pnl() {
        UUID tradeId = UUID.randomUUID();
        when(transactionRepository.existsById(tradeId)).thenReturn(false);

        // 평균 단가 800 에 사서 1000 에 2개 매도 → 실현이익 400
        ledgerService.recordDoubleEntry(cmd(tradeId, "SELL", "KRW", "KRW",
                Money.of("2", AssetType.CRYPTO, "BTC"), new BigDecimal("1000"), BigDecimal.ONE, new BigDecimal("800")));

        Transaction tx = capture();
        assertThat(debitTotal(tx)).isEqualByComparingTo("2000");
        assertThat(creditTotal(tx)).isEqualByComparingTo("2000");

        BigDecimal pnl = tx.getEntries().stream()
                .filter(e -> e.getRealizedPnl() != null)
                .map(e -> e.getRealizedPnl().getAmount())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(pnl).as("realized_pnl 컬럼은 참고값으로 그대로 남는다").isEqualByComparingTo("400");

        // 이익 400 은 고객 계정의 대변 분개(REALIZED_PNL_TRADING)로도 기록되어, 대변 = 원가 1600 + 이익 400 이다.
        assertThat(pnlEntries(tx)).singleElement().satisfies(e -> {
            assertThat(e.getEntryType()).isEqualTo(EntryType.CREDIT);
            assertThat(e.getAssetCode()).isEqualTo("REALIZED_PNL_TRADING");
            assertThat(e.getAmount().getAmount()).isEqualByComparingTo("400");
        });
    }

    @Test
    @DisplayName("매도 손실은 고객 계정의 차변 손익 분개로 기록하고 균형을 맞춘다")
    void sell_with_loss_records_a_debit_pnl_entry() {
        UUID tradeId = UUID.randomUUID();
        when(transactionRepository.existsById(tradeId)).thenReturn(false);

        // 평균 단가 1200 에 사서 1000 에 2개 매도 → 실현 손실 400
        ledgerService.recordDoubleEntry(cmd(tradeId, "SELL", "KRW", "KRW",
                Money.of("2", AssetType.CRYPTO, "BTC"), new BigDecimal("1000"), BigDecimal.ONE, new BigDecimal("1200")));

        Transaction tx = capture();
        assertThat(pnlEntries(tx)).singleElement().satisfies(e -> {
            assertThat(e.getEntryType()).isEqualTo(EntryType.DEBIT);
            assertThat(e.getAmount().getAmount()).isEqualByComparingTo("400");
        });
        // 차변 = 수취 2000 + 손실 400, 대변 = 원가 2400
        assertThat(debitTotal(tx)).isEqualByComparingTo("2400");
        assertThat(creditTotal(tx)).isEqualByComparingTo("2400");
    }

    @Test
    @DisplayName("baseCurrency 가 없으면 결제 통화를 기준 통화로 사용한다")
    void falls_back_to_fiat_code_as_base_currency() {
        UUID tradeId = UUID.randomUUID();
        when(transactionRepository.existsById(tradeId)).thenReturn(false);

        ledgerService.recordDoubleEntry(cmd(tradeId, "SELL", "USD", null,
                Money.of("1", AssetType.CRYPTO, "BTC"), new BigDecimal("100"), BigDecimal.ONE, new BigDecimal("90")));

        assertThat(capture().getEntries())
                .allSatisfy(e -> assertThat(e.getAmount().getCurrencyCode()).isEqualTo("USD"));
    }

    @Test
    @DisplayName("수수료 차감은 고객 대변과 시스템 수수료 계정 차변으로 균형을 맞춘다")
    void fee_deduction_balances() {
        UUID tradeId = UUID.randomUUID();
        when(transactionRepository.existsById(tradeId)).thenReturn(false);

        ledgerService.recordDoubleEntry(cmd(tradeId, "FEE_DEDUCTION", "KRW", "KRW",
                Money.of("1", AssetType.CRYPTO, "BTC"), new BigDecimal("500"), BigDecimal.ONE, new BigDecimal("400")));

        Transaction tx = capture();
        assertThat(debitTotal(tx)).isEqualByComparingTo(creditTotal(tx));
    }

    @Test
    @DisplayName("수수료 보정(초과 수취)은 고객 계좌 차변으로 기록되고 균형을 맞춘다")
    void fee_adjustment_gain_balances() {
        UUID tradeId = UUID.randomUUID();
        when(transactionRepository.existsById(tradeId)).thenReturn(false);

        LedgerRecordingCommand command = cmd(tradeId, "FEE_ADJUSTMENT", "KRW", "KRW",
                Money.of("50", AssetType.FIAT, "KRW"), BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO);

        ledgerService.recordDoubleEntry(command);

        Transaction tx = capture();
        assertThat(debitTotal(tx)).isEqualByComparingTo(creditTotal(tx));

        // 차변(입금)은 반드시 커맨드의 고객 계좌로 귀속되어야 한다.
        assertThat(tx.getEntries().stream()
                .filter(e -> e.getEntryType() == EntryType.DEBIT)
                .map(e -> e.getAccountId()))
                .contains(command.accountId());
    }

    @Test
    @DisplayName("수수료 보정(초과 지불)도 균형을 맞춘다")
    void fee_adjustment_loss_balances() {
        UUID tradeId = UUID.randomUUID();
        when(transactionRepository.existsById(tradeId)).thenReturn(false);

        ledgerService.recordDoubleEntry(cmd(tradeId, "FEE_ADJUSTMENT", "KRW", "KRW",
                Money.of("-50", AssetType.FIAT, "KRW"), BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO));

        Transaction tx = capture();
        assertThat(debitTotal(tx)).isEqualByComparingTo(creditTotal(tx));
    }

    @Test
    @DisplayName("수수료 보정 금액이 0 이면 엔트리를 만들지 않는다")
    void fee_adjustment_zero_creates_no_entries() {
        UUID tradeId = UUID.randomUUID();
        when(transactionRepository.existsById(tradeId)).thenReturn(false);

        ledgerService.recordDoubleEntry(cmd(tradeId, "FEE_ADJUSTMENT", "KRW", "KRW",
                Money.of("0", AssetType.FIAT, "KRW"), BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO));

        assertThat(capture().getEntries()).isEmpty();
    }

    @Test
    @DisplayName("알 수 없는 거래 유형은 조용히 빈 분개를 만들지 않고 거부한다")
    void rejects_unknown_trade_type() {
        UUID tradeId = UUID.randomUUID();
        when(transactionRepository.existsById(tradeId)).thenReturn(false);

        assertThatThrownBy(() -> ledgerService.recordDoubleEntry(cmd(tradeId, "TRANSFER", "KRW", "KRW",
                Money.of("1", AssetType.CRYPTO, "BTC"), new BigDecimal("100"), BigDecimal.ONE, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported trade type");

        verify(transactionRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("반올림 잔차 범위의 차액은 시스템 계정으로 흡수한다")
    void absorbs_rounding_residual() {
        UUID tradeId = UUID.randomUUID();
        when(transactionRepository.existsById(tradeId)).thenReturn(false);

        // KRW(스케일 0) 기준. 환율 소수로 인해 차변/대변 정규화 결과가 1원 단위로 어긋날 수 있다.
        ledgerService.recordDoubleEntry(cmd(tradeId, "BUY", "USD", "KRW",
                Money.of("1", AssetType.CRYPTO, "BTC"), new BigDecimal("10.5"), new BigDecimal("1300.7"), null));

        Transaction tx = capture();
        assertThat(debitTotal(tx))
                .as("플러그 엔트리 추가 후 대차가 정확히 일치해야 한다")
                .isEqualByComparingTo(creditTotal(tx));
        // 검증이 통과했다는 것 자체가 불변식이 지켜졌다는 뜻이다.
        tx.verifyDoubleEntry();
    }

    @Test
    @DisplayName("애그리거트의 대차 검증은 저장 전에 명시적으로 수행되고, 불균형을 그대로 통과시키지 않는다")
    void verify_double_entry_is_public_and_enforced() {
        Transaction tx = Transaction.record(UUID.randomUUID(), "BUY", "manual", TRADED_AT);
        tx.addBuyEntry(UUID.randomUUID(), "BTC", Money.of("1", AssetType.CRYPTO, "BTC"),
                new BigDecimal("100"), BigDecimal.ONE, "KRW");

        // 대변이 없으므로 불균형이다. JPA 콜백(@PreUpdate)은 부모 행이 dirty 하지 않으면 발동하지
        // 않으므로, 애플리케이션이 저장 전에 직접 호출해 즉시 검출해야 한다.
        assertThatThrownBy(tx::verifyDoubleEntry)
                .isInstanceOf(DoubleEntryImbalanceException.class)
                .hasMessageContaining("KRW");
    }

    @Test
    @DisplayName("모든 분개 경로는 플러그 허용 한도(엔트리 수 × 통화 최소 단위) 안에서만 균형을 맞춘다")
    void plug_stays_within_rounding_allowance() {
        UUID tradeId = UUID.randomUUID();
        when(transactionRepository.existsById(tradeId)).thenReturn(false);

        ledgerService.recordDoubleEntry(cmd(tradeId, "BUY", "USD", "KRW",
                Money.of("7", AssetType.CRYPTO, "BTC"), new BigDecimal("3.33"), new BigDecimal("1301.77"), null));

        Transaction tx = capture();

        // 플러그 엔트리가 있다면 그 금액은 반올림 잔차 수준이어야 한다.
        BigDecimal plug = tx.getEntries().stream()
                .filter(e -> e.getAssetCode().startsWith("SYSTEM_FX"))
                .map(e -> e.getAmount().getAmount().abs())
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal allowance = BigDecimal.ONE.multiply(BigDecimal.valueOf(tx.getEntries().size()));
        assertThat(plug)
                .as("플러그 금액이 반올림 허용 한도를 넘으면 계산 오류를 숨기는 것이다")
                .isLessThanOrEqualTo(allowance);
    }

    private static final UUID CASH_CLEARING_ACCOUNT_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");

    /** 입출금 커맨드. 페이로드의 자산 코드와 결제 통화는 모두 입출금 통화다. */
    private LedgerRecordingCommand cashCmd(UUID tradeId, String tradeType, String currency, String baseCurrency,
                                           String amount, BigDecimal fiatToBaseRate) {
        return new LedgerRecordingCommand(
                tradeId, UUID.randomUUID(), currency, AssetType.FIAT, currency, baseCurrency, tradeType,
                Money.of(amount, AssetType.FIAT, currency), BigDecimal.ONE, BigDecimal.ONE, fiatToBaseRate,
                BigDecimal.ZERO, false, TRADED_AT);
    }

    /** 한 자산 분개의 금액. 같은 계정의 손익 분개(REALIZED_PNL_*)와 섞이지 않게 자산 코드로 거른다. */
    private BigDecimal amountOf(Transaction tx, UUID accountId, EntryType entryType, String assetCode) {
        return tx.getEntries().stream()
                .filter(e -> e.getAccountId().equals(accountId) && e.getEntryType() == entryType
                        && e.getAssetCode().equals(assetCode))
                .map(e -> e.getAmount().getAmount())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal amountOf(Transaction tx, UUID accountId, EntryType entryType) {
        return tx.getEntries().stream()
                .filter(e -> e.getAccountId().equals(accountId) && e.getEntryType() == entryType)
                .map(e -> e.getAmount().getAmount())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Test
    @DisplayName("입금은 고객 현금 차변과 입출금 청산 계정 대변으로 기록된다")
    void deposit_debits_customer_and_credits_clearing() {
        UUID tradeId = UUID.randomUUID();
        when(transactionRepository.existsById(tradeId)).thenReturn(false);
        LedgerRecordingCommand command = cashCmd(tradeId, "DEPOSIT", "KRW", "KRW", "10000", BigDecimal.ONE);

        ledgerService.recordDoubleEntry(command);

        Transaction tx = capture();
        assertThat(amountOf(tx, command.accountId(), EntryType.DEBIT)).isEqualByComparingTo("10000");
        assertThat(amountOf(tx, CASH_CLEARING_ACCOUNT_ID, EntryType.CREDIT)).isEqualByComparingTo("10000");
        assertThat(debitTotal(tx)).isEqualByComparingTo(creditTotal(tx));
    }

    @Test
    @DisplayName("출금은 입출금 청산 계정 차변과 고객 현금 대변으로 기록된다")
    void withdrawal_debits_clearing_and_credits_customer() {
        UUID tradeId = UUID.randomUUID();
        when(transactionRepository.existsById(tradeId)).thenReturn(false);
        LedgerRecordingCommand command = cashCmd(tradeId, "WITHDRAWAL", "KRW", "KRW", "3000", BigDecimal.ONE);

        ledgerService.recordDoubleEntry(command);

        Transaction tx = capture();
        assertThat(amountOf(tx, CASH_CLEARING_ACCOUNT_ID, EntryType.DEBIT)).isEqualByComparingTo("3000");
        assertThat(amountOf(tx, command.accountId(), EntryType.CREDIT)).isEqualByComparingTo("3000");
        assertThat(debitTotal(tx)).isEqualByComparingTo(creditTotal(tx));
    }

    @Test
    @DisplayName("기준 통화가 아닌 통화로 입금하면 거래 시점 환율로 환산해 기록한다")
    void foreign_currency_deposit_is_converted_with_command_rate() {
        UUID tradeId = UUID.randomUUID();
        when(transactionRepository.existsById(tradeId)).thenReturn(false);
        LedgerRecordingCommand command = cashCmd(tradeId, "DEPOSIT", "USD", "KRW", "100", new BigDecimal("1300"));

        ledgerService.recordDoubleEntry(command);

        Transaction tx = capture();
        // 100 USD × 1300 = 130000 KRW
        assertThat(amountOf(tx, command.accountId(), EntryType.DEBIT)).isEqualByComparingTo("130000");
        assertThat(amountOf(tx, CASH_CLEARING_ACCOUNT_ID, EntryType.CREDIT)).isEqualByComparingTo("130000");
        assertThat(debitTotal(tx)).isEqualByComparingTo(creditTotal(tx));
    }

    /** 외화 출금 커맨드. 평균 단가는 출금 직전 외화 원장의 기준 통화 환산 단가다. */
    private LedgerRecordingCommand foreignWithdrawalCmd(UUID tradeId, String amount, String rate, String averageCost) {
        return new LedgerRecordingCommand(
                tradeId, UUID.randomUUID(), "USD", AssetType.FIAT, "USD", "KRW", "WITHDRAWAL",
                Money.of(amount, AssetType.FIAT, "USD"), BigDecimal.ONE, BigDecimal.ONE, new BigDecimal(rate),
                new BigDecimal(averageCost), false, TRADED_AT);
    }

    private BigDecimal realizedPnlOf(Transaction tx, UUID accountId) {
        return tx.getEntries().stream()
                .filter(e -> e.getAccountId().equals(accountId) && e.getRealizedPnl() != null)
                .map(e -> e.getRealizedPnl().getAmount())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Test
    @DisplayName("외화를 평균 단가보다 높은 환율에 출금하면 실현 환차익을 기록한다")
    void foreign_withdrawal_realizes_fx_gain_against_average_cost() {
        UUID tradeId = UUID.randomUUID();
        when(transactionRepository.existsById(tradeId)).thenReturn(false);
        // 1,300 원에 들어온 100 USD 를 1,400 원일 때 출금한다.
        LedgerRecordingCommand command = foreignWithdrawalCmd(tradeId, "100", "1400", "1300");

        ledgerService.recordDoubleEntry(command);

        Transaction tx = capture();
        // 청산 계정은 시가(100 × 1,400), 고객 외화는 원가(100 × 1,300)로 나가고 차액이 실현 손익이다.
        assertThat(amountOf(tx, CASH_CLEARING_ACCOUNT_ID, EntryType.DEBIT)).isEqualByComparingTo("140000");
        assertThat(amountOf(tx, command.accountId(), EntryType.CREDIT, "USD")).isEqualByComparingTo("130000");
        assertThat(realizedPnlOf(tx, command.accountId())).isEqualByComparingTo("10000");
        assertThat(debitTotal(tx)).isEqualByComparingTo(creditTotal(tx));
        // 청산 차변, 고객 USD 대변, 고객 환차익 대변. 손익 분개로 대차가 맞으므로 시스템 플러그는 없다.
        assertThat(tx.getEntries()).hasSize(3);
        assertThat(pnlEntries(tx)).singleElement().satisfies(e -> {
            assertThat(e.getAccountId()).isEqualTo(command.accountId());
            assertThat(e.getEntryType()).isEqualTo(EntryType.CREDIT);
            assertThat(e.getAssetCode()).isEqualTo("REALIZED_PNL_FX");
            assertThat(e.getAmount().getAmount()).isEqualByComparingTo("10000");
        });
    }

    @Test
    @DisplayName("외화를 평균 단가보다 낮은 환율에 출금하면 실현 환차손을 기록한다")
    void foreign_withdrawal_realizes_fx_loss_against_average_cost() {
        UUID tradeId = UUID.randomUUID();
        when(transactionRepository.existsById(tradeId)).thenReturn(false);
        LedgerRecordingCommand command = foreignWithdrawalCmd(tradeId, "100", "1300", "1400");

        ledgerService.recordDoubleEntry(command);

        Transaction tx = capture();
        assertThat(realizedPnlOf(tx, command.accountId())).isEqualByComparingTo("-10000");
        assertThat(debitTotal(tx)).isEqualByComparingTo(creditTotal(tx));
        assertThat(tx.getEntries()).hasSize(3);
        assertThat(pnlEntries(tx)).singleElement().satisfies(e -> {
            assertThat(e.getEntryType()).isEqualTo(EntryType.DEBIT);
            assertThat(e.getAssetCode()).isEqualTo("REALIZED_PNL_FX");
            assertThat(e.getAmount().getAmount()).isEqualByComparingTo("10000");
        });
    }

    @Test
    @DisplayName("평균 단가가 없는 출금 이벤트(배포 전 적재분)는 손익 없이 거래 시점 환율로 기록한다")
    void legacy_withdrawal_without_average_cost_keeps_rate_based_credit() {
        UUID tradeId = UUID.randomUUID();
        when(transactionRepository.existsById(tradeId)).thenReturn(false);
        // 배포 전 아웃박스에 쌓인 출금 이벤트는 평균 단가 자리에 0 이 들어 있다.
        LedgerRecordingCommand command = foreignWithdrawalCmd(tradeId, "100", "1400", "0");

        ledgerService.recordDoubleEntry(command);

        Transaction tx = capture();
        assertThat(amountOf(tx, command.accountId(), EntryType.CREDIT)).isEqualByComparingTo("140000");
        assertThat(realizedPnlOf(tx, command.accountId())).isEqualByComparingTo("0");
        assertThat(pnlEntries(tx)).as("손익이 없으면 손익 분개도 만들지 않는다").isEmpty();
        assertThat(debitTotal(tx)).isEqualByComparingTo(creditTotal(tx));
    }

    @Test
    @DisplayName("외화로 결제한 매수는 결제 통화의 평균 단가로 실현 환차손익을 기록한다")
    void buy_paid_in_foreign_currency_realizes_fx_pnl_on_payment_leg() {
        UUID tradeId = UUID.randomUUID();
        when(transactionRepository.existsById(tradeId)).thenReturn(false);
        // 0.001 BTC × 100,000 USD = 100 USD 를 결제. USD 는 1,300 원에 들어왔고 지금은 1,400 원이다.
        LedgerRecordingCommand command = cmd(tradeId, "BUY", "USD", "KRW",
                Money.of("0.001", AssetType.CRYPTO, "BTC"), new BigDecimal("100000"),
                new BigDecimal("1400"), new BigDecimal("1300"));

        ledgerService.recordDoubleEntry(command);

        Transaction tx = capture();
        // 자산은 시가(100 USD × 1,400)로 들어오고, USD 는 원가(100 × 1,300)로 나가며 차액이 실현 손익이다.
        assertThat(amountOf(tx, command.accountId(), EntryType.DEBIT)).isEqualByComparingTo("140000");
        assertThat(amountOf(tx, command.accountId(), EntryType.CREDIT, "USD")).isEqualByComparingTo("130000");
        assertThat(realizedPnlOf(tx, command.accountId())).isEqualByComparingTo("10000");
        assertThat(debitTotal(tx)).isEqualByComparingTo(creditTotal(tx));
        assertThat(tx.getEntries()).hasSize(3);
        // 나가는 쪽이 외화이므로 자산 매매 손익이 아니라 환차손익이다.
        assertThat(pnlEntries(tx)).singleElement().satisfies(e -> {
            assertThat(e.getAssetCode()).isEqualTo("REALIZED_PNL_FX");
            assertThat(e.getEntryType()).isEqualTo(EntryType.CREDIT);
            assertThat(e.getAmount().getAmount()).isEqualByComparingTo("10000");
        });
    }

    @Test
    @DisplayName("기준 통화로 거래하면 손익 분개가 생기지 않는다")
    void base_currency_trade_has_no_pnl_entry() {
        UUID tradeId = UUID.randomUUID();
        when(transactionRepository.existsById(tradeId)).thenReturn(false);

        ledgerService.recordDoubleEntry(cmd(tradeId, "BUY", "KRW", "KRW",
                Money.of("3", AssetType.CRYPTO, "BTC"), new BigDecimal("1000"), BigDecimal.ONE, BigDecimal.ONE));

        assertThat(pnlEntries(capture())).isEmpty();
    }
}
