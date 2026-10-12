package com.github.raonjena99.multi_currency_ledger_service.transaction.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.github.raonjena99.multi_currency_ledger_service.account.AccountApi;
import com.github.raonjena99.multi_currency_ledger_service.common.domain.Money;
import com.github.raonjena99.multi_currency_ledger_service.common.model.AssetType;
import com.github.raonjena99.multi_currency_ledger_service.common.model.EntryType;
import com.github.raonjena99.multi_currency_ledger_service.transaction.application.command.FxConversionDetail;
import com.github.raonjena99.multi_currency_ledger_service.transaction.application.command.LedgerRecordingCommand;
import com.github.raonjena99.multi_currency_ledger_service.transaction.domain.Transaction;
import com.github.raonjena99.multi_currency_ledger_service.transaction.domain.TransactionEntry;
import com.github.raonjena99.multi_currency_ledger_service.transaction.infrastructure.TransactionRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * 환전({@code FX_CONVERSION}) 분개를 검증합니다.
 *
 * <p>환전은 한 거래 안에서 두 쪽이 각각 균형을 이룹니다. 고객 쪽은 (낸 통화 대변, 받은 통화 차변)이고, 회사 쪽은
 * 시스템 FX 계정이 반대편 포지션을 가지며 시세와 고객 금액의 차이가 스프레드 수익(시스템 수수료 계정)이 됩니다.
 * 실현 손익은 외화를 내보낼 때 고객 계정의 별도 분개로 남습니다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("단위 테스트: LedgerService 환전 분개")
class LedgerServiceFxConversionTest {

    private static final OffsetDateTime TRADED_AT = OffsetDateTime.parse("2026-07-15T10:00:00Z");
    private static final UUID SYSTEM_FX = new UUID(0, 0);
    private static final UUID SYSTEM_FEE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String SPREAD = "SYSTEM_FX_SPREAD";

    @Mock private TransactionRepository transactionRepository;
    @Mock private AccountApi accountApi;

    private LedgerService ledgerService;
    private final UUID accountId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        ledgerService = new LedgerService(transactionRepository, accountApi, new SimpleMeterRegistry());
    }

    /**
     * 환전 커맨드. {@code foreign} 은 외화 금액이고, 방향에 따라 고객이 내는 쪽이거나 받는 쪽이다.
     */
    private LedgerRecordingCommand fx(String direction, String baseCurrency, String foreignCurrency,
                                      String foreignAmount, String baseAmount, String marketValue,
                                      String averageCost) {
        BigDecimal foreign = new BigDecimal(foreignAmount);
        BigDecimal unit = new BigDecimal(baseAmount).divide(foreign, 18, java.math.RoundingMode.HALF_EVEN);
        return new LedgerRecordingCommand(UUID.randomUUID(), accountId, foreignCurrency, AssetType.FIAT,
                baseCurrency, baseCurrency, "FX_CONVERSION", Money.of(foreignAmount, AssetType.FIAT, foreignCurrency),
                unit, BigDecimal.ONE, BigDecimal.ONE, averageCost == null ? null : new BigDecimal(averageCost),
                false, TRADED_AT,
                new FxConversionDetail(direction, new BigDecimal(baseAmount), new BigDecimal(marketValue)));
    }

    private Transaction record(LedgerRecordingCommand command) {
        when(transactionRepository.existsById(command.referenceTradeId())).thenReturn(false);
        ledgerService.recordDoubleEntry(command);
        ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository).saveAndFlush(captor.capture());
        return captor.getValue();
    }

    private static BigDecimal total(Transaction tx, EntryType side) {
        return tx.getEntries().stream().filter(e -> e.getEntryType() == side)
                .map(e -> e.getAmount().getAmount()).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static List<TransactionEntry> entries(Transaction tx, UUID account, EntryType side, String assetCode) {
        return tx.getEntries().stream()
                .filter(e -> e.getAccountId().equals(account) && e.getEntryType() == side
                        && e.getAssetCode().equals(assetCode))
                .toList();
    }

    private static TransactionEntry single(Transaction tx, UUID account, EntryType side, String assetCode) {
        List<TransactionEntry> found = entries(tx, account, side, assetCode);
        assertThat(found).as("%s %s %s 분개", account, side, assetCode).hasSize(1);
        return found.get(0);
    }

    // ── 외화 매수 (KRW → USD) ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("외화 매수: 고객은 원화를 내고 USD 를 원가(낸 원화)로 받고, 회사는 시세로 USD 를 내주며 차이를 스프레드로 번다")
    void buyForeignBalancesBothSidesAndBooksSpread() {
        // 시세 1,300 · 고객 환율 1,322.75(스프레드 1.75%): 1,322,750 원 → 1,000 USD. 시세 가치 1,300,000, 스프레드 22,750.
        Transaction tx = record(fx("BUY_FOREIGN", "KRW", "USD", "1000", "1322750", "1300000", null));

        TransactionEntry customerUsd = single(tx, accountId, EntryType.DEBIT, "USD");
        assertThat(customerUsd.getQuantity().getAmount()).isEqualByComparingTo("1000");
        assertThat(customerUsd.getAmount().getAmount()).as("취득 원가 = 낸 원화").isEqualByComparingTo("1322750");
        assertThat(single(tx, accountId, EntryType.CREDIT, "KRW").getAmount().getAmount())
                .isEqualByComparingTo("1322750");

        assertThat(single(tx, SYSTEM_FX, EntryType.DEBIT, "KRW").getAmount().getAmount())
                .as("회사는 원화를 받는다").isEqualByComparingTo("1322750");
        TransactionEntry companyUsd = single(tx, SYSTEM_FX, EntryType.CREDIT, "USD");
        assertThat(companyUsd.getQuantity().getAmount()).as("회사는 USD 를 내준다").isEqualByComparingTo("1000");
        assertThat(companyUsd.getAmount().getAmount()).as("시세 가치").isEqualByComparingTo("1300000");

        TransactionEntry spread = single(tx, SYSTEM_FEE, EntryType.CREDIT, SPREAD);
        assertThat(spread.getAmount().getAmount()).isEqualByComparingTo("22750");
        assertThat(spread.getAmount().getCurrencyCode()).isEqualTo("KRW");

        assertThat(total(tx, EntryType.DEBIT)).isEqualByComparingTo(total(tx, EntryType.CREDIT));
        assertThat(tx.getEntries()).as("원화 계좌의 원화 지출은 평균 단가 1 이라 손익이 없다")
                .noneMatch(e -> e.getAssetCode().startsWith("REALIZED_PNL_"))
                .noneMatch(e -> e.getAssetCode().equals("SYSTEM_FX_GAIN") || e.getAssetCode().equals("SYSTEM_FX_LOSS"));
    }

    @Test
    @DisplayName("외화 매수: 달러 기준 계좌가 원화를 사면 분개는 달러(센트 단위)로 균형을 이룬다")
    void buyForeignInAUsdBaseAccountBalancesInCents() {
        // 1,310,319 KRW 를 1,000.00 USD 에 산다. 시세 가치는 1,310,319 × 0.00075 = 982.73925 → 982.73(내림), 스프레드 17.27.
        Transaction tx = record(fx("BUY_FOREIGN", "USD", "KRW", "1310319", "1000.00", "982.73", null));

        assertThat(single(tx, accountId, EntryType.DEBIT, "KRW").getAmount().getAmount()).isEqualByComparingTo("1000.00");
        assertThat(single(tx, SYSTEM_FX, EntryType.CREDIT, "KRW").getAmount().getAmount()).isEqualByComparingTo("982.73");
        assertThat(single(tx, SYSTEM_FEE, EntryType.CREDIT, SPREAD).getAmount().getAmount())
                .isEqualByComparingTo("17.27");
        assertThat(total(tx, EntryType.DEBIT)).isEqualByComparingTo(total(tx, EntryType.CREDIT));
    }

    // ── 외화 매도 (USD → KRW) ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("외화 매도: 평균 단가보다 싸게 팔면 고객 계정에 환차손(차변) 분개가 남고 양쪽이 균형을 이룬다")
    void sellForeignAtALossBooksAnFxLossForTheCustomer() {
        // 시세 1,300 · 고객 환율 1,277.25: 1,000 USD → 1,277,250 원. 평균 단가 1,322.75(원가 1,322,750) → 손실 45,500.
        Transaction tx = record(fx("SELL_FOREIGN", "KRW", "USD", "1000", "1277250", "1300000", "1322.75"));

        TransactionEntry customerUsd = single(tx, accountId, EntryType.CREDIT, "USD");
        assertThat(customerUsd.getQuantity().getAmount()).isEqualByComparingTo("1000");
        assertThat(customerUsd.getAmount().getAmount()).as("나가는 USD 는 평균 단가(원가)로 기록").isEqualByComparingTo("1322750");
        assertThat(single(tx, accountId, EntryType.DEBIT, "KRW").getAmount().getAmount()).isEqualByComparingTo("1277250");

        TransactionEntry pnl = single(tx, accountId, EntryType.DEBIT, "REALIZED_PNL_FX");
        assertThat(pnl.getAmount().getAmount()).isEqualByComparingTo("45500");

        TransactionEntry companyUsd = single(tx, SYSTEM_FX, EntryType.DEBIT, "USD");
        assertThat(companyUsd.getQuantity().getAmount()).as("회사는 USD 를 받는다").isEqualByComparingTo("1000");
        assertThat(companyUsd.getAmount().getAmount()).as("시세 가치").isEqualByComparingTo("1300000");
        assertThat(single(tx, SYSTEM_FX, EntryType.CREDIT, "KRW").getAmount().getAmount())
                .as("회사는 원화를 내준다").isEqualByComparingTo("1277250");
        assertThat(single(tx, SYSTEM_FEE, EntryType.CREDIT, SPREAD).getAmount().getAmount())
                .isEqualByComparingTo("22750");

        assertThat(total(tx, EntryType.DEBIT)).isEqualByComparingTo(total(tx, EntryType.CREDIT));
    }

    @Test
    @DisplayName("외화 매도: 평균 단가보다 비싸게 팔면 고객 계정에 환차익(대변) 분개가 남는다")
    void sellForeignAtAGainBooksAnFxGainForTheCustomer() {
        // 평균 단가 1,200(원가 1,200,000)인 USD 1,000 을 1,277,250 원에 판다 → 이익 77,250.
        Transaction tx = record(fx("SELL_FOREIGN", "KRW", "USD", "1000", "1277250", "1300000", "1200"));

        assertThat(single(tx, accountId, EntryType.CREDIT, "REALIZED_PNL_FX").getAmount().getAmount())
                .isEqualByComparingTo("77250");
        assertThat(total(tx, EntryType.DEBIT)).isEqualByComparingTo(total(tx, EntryType.CREDIT));
    }

    @Test
    @DisplayName("외화 매도: 평균 단가를 모르면(0) 손익을 인식하지 않는다")
    void sellForeignWithoutAnAverageCostRecognisesNoPnl() {
        Transaction tx = record(fx("SELL_FOREIGN", "KRW", "USD", "1000", "1277250", "1300000", "0"));

        assertThat(tx.getEntries()).noneMatch(e -> e.getAssetCode().startsWith("REALIZED_PNL_"));
        assertThat(total(tx, EntryType.DEBIT)).isEqualByComparingTo(total(tx, EntryType.CREDIT));
    }

    // ── 스프레드 ────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("스프레드 수익이 0 이면 수익 분개를 만들지 않는다")
    void noSpreadEntryWhenTheSpreadRoundsToZero() {
        Transaction tx = record(fx("BUY_FOREIGN", "KRW", "USD", "1000", "1300000", "1300000", null));

        assertThat(tx.getEntries()).noneMatch(e -> e.getAssetCode().equals(SPREAD));
        assertThat(total(tx, EntryType.DEBIT)).isEqualByComparingTo(total(tx, EntryType.CREDIT));
    }

    @Test
    @DisplayName("환전 왕복(원화 → USD → 원화)에서 고객 손실은 회사 스프레드 수익의 합과 같다")
    void roundTripCustomerLossEqualsCompanySpreadRevenue() {
        // 같은 시세 1,300 에서 사고 판다. 사기: 1,322,750 → 1,000 USD(원가 1,322.75). 팔기: 1,000 USD → 1,277,250.
        Transaction buy = record(fx("BUY_FOREIGN", "KRW", "USD", "1000", "1322750", "1300000", null));
        BigDecimal buyRevenue = single(buy, SYSTEM_FEE, EntryType.CREDIT, SPREAD).getAmount().getAmount();

        org.mockito.Mockito.reset(transactionRepository);
        Transaction sell = record(fx("SELL_FOREIGN", "KRW", "USD", "1000", "1277250", "1300000", "1322.75"));
        BigDecimal sellRevenue = single(sell, SYSTEM_FEE, EntryType.CREDIT, SPREAD).getAmount().getAmount();
        BigDecimal customerLoss = single(sell, accountId, EntryType.DEBIT, "REALIZED_PNL_FX").getAmount().getAmount();

        // 고객이 낸 돈 − 돌려받은 돈 = 1,322,750 − 1,277,250 = 45,500
        assertThat(customerLoss).isEqualByComparingTo("45500");
        assertThat(buyRevenue.add(sellRevenue)).isEqualByComparingTo(customerLoss);
    }

    @Test
    @DisplayName("스프레드 수익이 음수가 되는 금액은 거부한다(회사가 손해 보는 환전을 장부에 남기지 않는다)")
    void rejectsANegativeSpread() {
        // 외화 매수인데 시세 가치(1,400,000)가 고객이 낸 금액(1,322,750)보다 크다.
        LedgerRecordingCommand buy = fx("BUY_FOREIGN", "KRW", "USD", "1000", "1322750", "1400000", null);
        when(transactionRepository.existsById(buy.referenceTradeId())).thenReturn(false);

        assertThatThrownBy(() -> ledgerService.recordDoubleEntry(buy))
                .isInstanceOf(com.github.raonjena99.multi_currency_ledger_service.common.exception
                        .DoubleEntryImbalanceException.class)
                .hasMessageContaining("음수");
    }

    // ── 잘못된 입력 ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("환전 상세가 없으면 거부한다")
    void rejectsAConversionWithoutDetail() {
        LedgerRecordingCommand command = new LedgerRecordingCommand(UUID.randomUUID(), accountId, "USD",
                AssetType.FIAT, "KRW", "KRW", "FX_CONVERSION", Money.of("1000", AssetType.FIAT, "USD"),
                BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, false, TRADED_AT);
        when(transactionRepository.existsById(command.referenceTradeId())).thenReturn(false);

        assertThatThrownBy(() -> ledgerService.recordDoubleEntry(command))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("fx 상세");
    }

    @Test
    @DisplayName("알 수 없는 방향은 거부한다")
    void rejectsAnUnknownDirection() {
        LedgerRecordingCommand command = fx("SIDEWAYS", "KRW", "USD", "1000", "1322750", "1300000", null);
        when(transactionRepository.existsById(command.referenceTradeId())).thenReturn(false);

        assertThatThrownBy(() -> ledgerService.recordDoubleEntry(command))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("SIDEWAYS");
    }
}
