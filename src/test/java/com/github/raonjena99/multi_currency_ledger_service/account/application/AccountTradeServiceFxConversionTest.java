package com.github.raonjena99.multi_currency_ledger_service.account.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;

import com.github.raonjena99.multi_currency_ledger_service.account.domain.Account;
import com.github.raonjena99.multi_currency_ledger_service.account.domain.FxConversion;
import com.github.raonjena99.multi_currency_ledger_service.account.domain.FxDirection;
import com.github.raonjena99.multi_currency_ledger_service.account.domain.FxQuote;
import com.github.raonjena99.multi_currency_ledger_service.account.domain.IdempotencyRecord;
import com.github.raonjena99.multi_currency_ledger_service.account.domain.MonthlyAccountLedger;
import com.github.raonjena99.multi_currency_ledger_service.account.domain.event.TradeExecutedEvent;
import com.github.raonjena99.multi_currency_ledger_service.account.infrastructure.AccountRepository;
import com.github.raonjena99.multi_currency_ledger_service.account.infrastructure.FxConversionRepository;
import com.github.raonjena99.multi_currency_ledger_service.account.infrastructure.IdempotencyRecordRepository;
import com.github.raonjena99.multi_currency_ledger_service.account.infrastructure.MonthlyAccountLedgerRepository;
import com.github.raonjena99.multi_currency_ledger_service.common.domain.Money;
import com.github.raonjena99.multi_currency_ledger_service.common.exception.FxQuoteAlreadyUsedException;
import com.github.raonjena99.multi_currency_ledger_service.common.exception.InsufficientBalanceException;
import com.github.raonjena99.multi_currency_ledger_service.common.exception.InvalidAccountStateException;
import com.github.raonjena99.multi_currency_ledger_service.common.model.AssetType;
import com.github.raonjena99.multi_currency_ledger_service.common.model.TradeType;

/**
 * 환전 체결(잔고 이동, 체결 기록, 원장 기록 이벤트)을 검증합니다.
 *
 * <p>월 원장은 목이 아니라 실제 도메인 객체를 씁니다. 평균 단가와 잔고 계산이 체결의 핵심이라서, 목으로 바꾸면 그 계산을
 * 검증하지 못합니다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("단위 테스트: 환전 체결")
class AccountTradeServiceFxConversionTest {

    private static final String MONTH = "2026-07";
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-07-15T10:00:00Z");

    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private AccountRepository accountRepository;
    @Mock private IdempotencyRecordRepository idempotencyRepository;
    @Mock private MonthlyAccountLedgerRepository monthlyAccountLedgerRepository;
    @Mock private MonthlyLedgerResolver monthlyLedgerResolver;
    @Mock private FxConversionRepository fxConversionRepository;

    @InjectMocks private AccountTradeService tradeService;

    private final UUID accountId = UUID.randomUUID();
    private final List<IdempotencyRecord> registered = new ArrayList<>();

    @BeforeEach
    void setUp() {
        stubAccount("KRW");
        lenient().when(idempotencyRepository.saveAndFlush(any(IdempotencyRecord.class))).thenAnswer(inv -> {
            registered.add(inv.getArgument(0));
            return inv.getArgument(0);
        });
        lenient().when(fxConversionRepository.saveAndFlush(any(FxConversion.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    private void stubAccount(String baseCurrency) {
        lenient().when(accountRepository.findByIdForShare(accountId))
                .thenReturn(Optional.of(Account.open(accountId, "FX_USER", baseCurrency)));
    }

    /** 잔고와 평균 단가(기준 통화)를 가진 월 원장. 잔고가 0 이면 비어 있는 원장이다. */
    private MonthlyAccountLedger ledger(String currency, String balance, String averageCost, String baseCurrency) {
        MonthlyAccountLedger ledger = MonthlyAccountLedger.initialize(accountId, currency, AssetType.FIAT, MONTH,
                baseCurrency);
        if (new BigDecimal(balance).signum() > 0) {
            ledger.addBalance(Money.of(balance, AssetType.FIAT, currency), new BigDecimal(averageCost));
        }
        return ledger;
    }

    private void stubLedgers(MonthlyAccountLedger... ledgers) {
        for (MonthlyAccountLedger ledger : ledgers) {
            lenient().when(monthlyLedgerResolver.resolveOrInitializeLedger(eq(accountId), eq(ledger.getAssetCode()),
                    eq(AssetType.FIAT), anyString())).thenReturn(ledger);
        }
    }

    private FxQuote quote(FxDirection direction, String from, String fromAmount, String to, String toAmount,
                          String marketRate, String customerRate) {
        return new FxQuote(UUID.randomUUID(), accountId, direction, from, new BigDecimal(fromAmount), to,
                new BigDecimal(toAmount), new BigDecimal(marketRate), new BigDecimal(customerRate),
                new BigDecimal("0.0175"), BigDecimal.ZERO, NOW, NOW.plusSeconds(30));
    }

    /** 1,322,750 원 → USD 1,000 (시세 1,300, 고객 환율 1,322.75). */
    private FxQuote buyUsdQuote() {
        return quote(FxDirection.BUY_FOREIGN, "KRW", "1322750", "USD", "1000", "1300", "1322.75");
    }

    /** USD 1,000 → 1,277,250 원 (시세 1,300, 고객 환율 1,277.25). */
    private FxQuote sellUsdQuote() {
        return quote(FxDirection.SELL_FOREIGN, "USD", "1000", "KRW", "1277250", "1300", "1277.25");
    }

    private TradeExecutedEvent publishedEvent() {
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(captor.capture());
        return (TradeExecutedEvent) captor.getValue();
    }

    // ── 외화 매수 ────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("외화 매수: 기준 통화 잔고가 줄고 외화 잔고가 원가(낸 금액 ÷ 받은 수량)로 늘며, 체결 기록의 시세 가치는 내림이다")
    void buyForeignMovesBalancesAtCost() {
        MonthlyAccountLedger krw = ledger("KRW", "2000000", "1", "KRW");
        MonthlyAccountLedger usd = ledger("USD", "0", "0", "KRW");
        stubLedgers(krw, usd);

        FxConversion conversion = tradeService.executeFxConversion("key-1", buyUsdQuote(), NOW, MONTH);

        assertThat(krw.getBalance().getAmount()).isEqualByComparingTo("677250");
        assertThat(usd.getBalance().getAmount()).isEqualByComparingTo("1000");
        assertThat(usd.getAverageUnitPrice()).as("평균 단가 = 낸 원화 ÷ 받은 USD").isEqualByComparingTo("1322.75");
        assertThat(conversion.getMarketValue()).as("USD 1,000 의 시세 가치").isEqualByComparingTo("1300000");
        assertThat(conversion.getSpreadRevenue()).isEqualByComparingTo("22750");
        verify(monthlyAccountLedgerRepository).save(krw);
        verify(monthlyAccountLedgerRepository).save(usd);
        verify(fxConversionRepository).saveAndFlush(conversion);
    }

    @Test
    @DisplayName("외화 매수: 이미 외화가 있으면 평균 단가는 가중 평균으로 갱신된다")
    void buyForeignUpdatesTheWeightedAverageCost() {
        MonthlyAccountLedger krw = ledger("KRW", "2000000", "1", "KRW");
        MonthlyAccountLedger usd = ledger("USD", "500", "1300", "KRW");
        stubLedgers(krw, usd);

        tradeService.executeFxConversion("key-1", buyUsdQuote(), NOW, MONTH);

        // (500 × 1,300 + 1,322,750) ÷ 1,500
        assertThat(usd.getAverageUnitPrice()).isCloseTo(new BigDecimal("1315.166666666666667"),
                within(new BigDecimal("0.000001")));
        assertThat(usd.getBalance().getAmount()).isEqualByComparingTo("1500");
    }

    @Test
    @DisplayName("외화 매수: 시세 가치는 내림이라 달러 기준 계좌의 원화 매수 스프레드는 센트 단위로 정해진다")
    void buyForeignRoundsTheMarketValueDownInTheBaseCurrencyScale() {
        stubAccount("USD");
        MonthlyAccountLedger usd = ledger("USD", "2000.00", "1", "USD");
        MonthlyAccountLedger krw = ledger("KRW", "0", "0", "USD");
        stubLedgers(usd, krw);
        // 1,000.00 USD → KRW 1,310,319. 시세 1 KRW = 0.00075 USD 라 시세 가치 982.73925 → 982.73, 스프레드 17.27.
        FxQuote quote = quote(FxDirection.BUY_FOREIGN, "USD", "1000.00", "KRW", "1310319", "0.00075", "0.000763125");

        FxConversion conversion = tradeService.executeFxConversion("key-1", quote, NOW, MONTH);

        assertThat(conversion.getMarketValue()).isEqualByComparingTo("982.73");
        assertThat(conversion.getSpreadRevenue()).isEqualByComparingTo("17.27");
        assertThat(publishedEvent().fx().marketValue()).isEqualByComparingTo("982.73");
    }

    // ── 외화 매도 ────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("외화 매도: 외화 잔고가 줄고 기준 통화 잔고가 늘며, 이벤트는 차감 직전 평균 단가를 싣는다")
    void sellForeignCarriesTheAverageCostOnTheEvent() {
        MonthlyAccountLedger usd = ledger("USD", "1000", "1322.75", "KRW");
        MonthlyAccountLedger krw = ledger("KRW", "0", "0", "KRW");
        stubLedgers(usd, krw);

        FxConversion conversion = tradeService.executeFxConversion("key-1", sellUsdQuote(), NOW, MONTH);

        assertThat(usd.getBalance().getAmount()).isEqualByComparingTo("0");
        assertThat(krw.getBalance().getAmount()).isEqualByComparingTo("1277250");
        assertThat(krw.getAverageUnitPrice()).as("기준 통화의 평균 단가는 1").isEqualByComparingTo("1");
        assertThat(conversion.getMarketValue()).isEqualByComparingTo("1300000");
        assertThat(conversion.getSpreadRevenue()).isEqualByComparingTo("22750");

        TradeExecutedEvent event = publishedEvent();
        assertThat(event.averageCost()).as("원장이 실현 손익을 계산하는 근거").isEqualByComparingTo("1322.75");
    }

    @Test
    @DisplayName("외화 매도: 시세 가치는 올림이라 회사 스프레드가 반올림 때문에 줄지 않는다")
    void sellForeignRoundsTheMarketValueUp() {
        MonthlyAccountLedger usd = ledger("USD", "1000", "1300", "KRW");
        MonthlyAccountLedger krw = ledger("KRW", "0", "0", "KRW");
        stubLedgers(usd, krw);
        // 1,000 × 1,300.1234 = 1,300,123.4 → 올림 1,300,124
        FxQuote quote = quote(FxDirection.SELL_FOREIGN, "USD", "1000", "KRW", "1277250", "1300.1234", "1277.25");

        FxConversion conversion = tradeService.executeFxConversion("key-1", quote, NOW, MONTH);

        assertThat(conversion.getMarketValue()).isEqualByComparingTo("1300124");
        assertThat(conversion.getSpreadRevenue()).isEqualByComparingTo("22874");
    }

    // ── 이벤트와 멱등성 ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("체결은 환전 이벤트를 발행하고 멱등성 레코드에 체결 ID 를 남긴다")
    void publishesTheConversionEventAndCompletesTheIdempotencyRecord() {
        stubLedgers(ledger("KRW", "2000000", "1", "KRW"), ledger("USD", "0", "0", "KRW"));

        FxConversion conversion = tradeService.executeFxConversion("key-1", buyUsdQuote(), NOW, MONTH);

        TradeExecutedEvent event = publishedEvent();
        assertThat(event.tradeId()).as("원장 거래 ID = 체결 ID").isEqualTo(conversion.getId());
        assertThat(event.accountId()).isEqualTo(accountId);
        assertThat(event.tradeType()).isEqualTo(TradeType.FX_CONVERSION);
        assertThat(event.assetCode()).isEqualTo("USD");
        assertThat(event.assetType()).isEqualTo(AssetType.FIAT);
        assertThat(event.fiatCode()).isEqualTo("KRW");
        assertThat(event.baseCurrency()).isEqualTo("KRW");
        assertThat(event.quantity()).as("외화 금액").isEqualByComparingTo("1000");
        assertThat(event.fx().direction()).isEqualTo(FxDirection.BUY_FOREIGN);
        assertThat(event.fx().baseAmount()).isEqualByComparingTo("1322750");
        assertThat(event.fx().marketValue()).isEqualByComparingTo("1300000");
        assertThat(event.occurredAt()).isEqualTo(NOW);

        assertThat(registered).singleElement().satisfies(record -> {
            assertThat(record.getIdempotencyKey()).isEqualTo(accountId + ":FX_CONVERSION:key-1");
            assertThat(record.getTradeId()).isEqualTo(conversion.getId());
        });
    }

    @Test
    @DisplayName("같은 멱등성 키로 이미 체결했으면 잔고를 건드리지 않고 처음 체결을 돌려준다")
    void replaysTheCompletedConversion() {
        FxQuote quote = buyUsdQuote();
        FxConversion existing = FxConversion.of(UUID.randomUUID(), quote, new BigDecimal("1300000"),
                new BigDecimal("22750"), NOW);
        IdempotencyRecord completed = new IdempotencyRecord(accountId + ":FX_CONVERSION:key-1");
        completed.complete(existing.getId());
        when(idempotencyRepository.findById(accountId + ":FX_CONVERSION:key-1")).thenReturn(Optional.of(completed));
        when(fxConversionRepository.findById(existing.getId())).thenReturn(Optional.of(existing));

        FxConversion replayed = tradeService.executeFxConversion("key-1", quote, NOW, MONTH);

        assertThat(replayed).isSameAs(existing);
        verifyNoInteractions(monthlyLedgerResolver);
        verifyNoInteractions(eventPublisher);
        verify(fxConversionRepository, never()).saveAndFlush(any());
    }

    // ── 거부 ────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("이미 체결된 견적은 잔고를 바꾸기 전에 거부한다")
    void rejectsAQuoteThatWasAlreadyConverted() {
        FxQuote quote = buyUsdQuote();
        stubLedgers(ledger("KRW", "2000000", "1", "KRW"), ledger("USD", "0", "0", "KRW"));
        when(fxConversionRepository.findByQuoteId(quote.quoteId())).thenReturn(Optional.of(
                FxConversion.of(UUID.randomUUID(), quote, new BigDecimal("1300000"), new BigDecimal("22750"), NOW)));

        assertThatThrownBy(() -> tradeService.executeFxConversion("key-2", quote, NOW, MONTH))
                .isInstanceOf(FxQuoteAlreadyUsedException.class);

        verify(monthlyAccountLedgerRepository, never()).save(any());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    @DisplayName("동시에 같은 견적을 체결해 유니크 제약에 걸리면 견적 사용 오류로 바꾼다")
    void translatesTheQuoteUniquenessViolation() {
        stubLedgers(ledger("KRW", "2000000", "1", "KRW"), ledger("USD", "0", "0", "KRW"));
        when(fxConversionRepository.saveAndFlush(any(FxConversion.class))).thenThrow(new DataIntegrityViolationException(
                "ERROR: duplicate key value violates unique constraint \"uq_fx_conversions_quote_id\""));

        assertThatThrownBy(() -> tradeService.executeFxConversion("key-1", buyUsdQuote(), NOW, MONTH))
                .isInstanceOf(FxQuoteAlreadyUsedException.class);
    }

    @Test
    @DisplayName("견적 유니크 제약이 아닌 무결성 오류는 견적 사용 오류로 숨기지 않는다")
    void doesNotMislabelOtherIntegrityViolations() {
        stubLedgers(ledger("KRW", "2000000", "1", "KRW"), ledger("USD", "0", "0", "KRW"));
        when(fxConversionRepository.saveAndFlush(any(FxConversion.class))).thenThrow(new DataIntegrityViolationException(
                "ERROR: insert or update on table \"fx_conversions\" violates foreign key constraint"));

        assertThatThrownBy(() -> tradeService.executeFxConversion("key-1", buyUsdQuote(), NOW, MONTH))
                .isInstanceOf(DataIntegrityViolationException.class)
                .isNotInstanceOf(FxQuoteAlreadyUsedException.class);
    }

    @Test
    @DisplayName("잔고가 모자라면 아무것도 기록하지 않고 거부한다")
    void rejectsAnInsufficientBalance() {
        stubLedgers(ledger("KRW", "1000", "1", "KRW"), ledger("USD", "0", "0", "KRW"));

        assertThatThrownBy(() -> tradeService.executeFxConversion("key-1", buyUsdQuote(), NOW, MONTH))
                .isInstanceOf(InsufficientBalanceException.class);

        verify(fxConversionRepository, never()).saveAndFlush(any());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    @DisplayName("정지된 계좌는 환전할 수 없다")
    void rejectsASuspendedAccount() {
        Account account = Account.open(accountId, "FX_USER", "KRW");
        account.suspend();
        when(accountRepository.findByIdForShare(accountId)).thenReturn(Optional.of(account));

        assertThatThrownBy(() -> tradeService.executeFxConversion("key-1", buyUsdQuote(), NOW, MONTH))
                .isInstanceOf(InvalidAccountStateException.class);

        verifyNoInteractions(monthlyLedgerResolver);
        verifyNoInteractions(eventPublisher);
    }
}
