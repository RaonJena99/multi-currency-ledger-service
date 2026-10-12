package com.github.raonjena99.multi_currency_ledger_service.account.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.github.raonjena99.multi_currency_ledger_service.account.domain.Account;
import com.github.raonjena99.multi_currency_ledger_service.account.domain.FxConversion;
import com.github.raonjena99.multi_currency_ledger_service.account.domain.FxDirection;
import com.github.raonjena99.multi_currency_ledger_service.account.domain.FxQuote;
import com.github.raonjena99.multi_currency_ledger_service.account.infrastructure.AccountRepository;
import com.github.raonjena99.multi_currency_ledger_service.account.infrastructure.FxConversionRepository;
import com.github.raonjena99.multi_currency_ledger_service.common.exception.AccountNotFoundException;
import com.github.raonjena99.multi_currency_ledger_service.common.exception.FxQuoteAlreadyUsedException;
import com.github.raonjena99.multi_currency_ledger_service.common.exception.FxQuoteExpiredException;
import com.github.raonjena99.multi_currency_ledger_service.common.exception.FxQuoteUnavailableException;
import com.github.raonjena99.multi_currency_ledger_service.common.exception.InvalidAccountStateException;
import com.github.raonjena99.multi_currency_ledger_service.common.model.AssetType;

/**
 * 환전 체결 요청의 앞단(견적 확인, 멱등 재전송, 원장 준비, 견적 정리)을 검증합니다.
 *
 * <p>견적이 없거나 만료되었거나 다른 계좌의 것이면 모두 같은 오류입니다. 고객이 할 일이 같고(새 견적을 받는다), 다른 계좌의
 * 견적이 존재하는지를 알려 주지 않기 위해서입니다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("단위 테스트: 환전 체결 Facade")
class AccountFxConversionFacadeTest {

    private static final String MONTH = "2026-07";

    @Mock private FxQuoteStore quoteStore;
    @Mock private AccountTradeService tradeService;
    @Mock private FxConversionRepository conversionRepository;
    @Mock private AccountRepository accountRepository;
    @Mock private LedgerPeriodResolver periodResolver;
    @Mock private MonthlyLedgerResolver ledgerResolver;

    @InjectMocks private AccountFxConversionFacade facade;

    private final UUID accountId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        org.mockito.Mockito.lenient().when(accountRepository.findById(accountId))
                .thenReturn(Optional.of(Account.open(accountId, "FX_USER", "KRW")));
        org.mockito.Mockito.lenient().when(periodResolver.resolveLedgerMonth(eq(accountId), any(OffsetDateTime.class)))
                .thenReturn(MONTH);
    }

    private FxQuote quote(UUID owner, OffsetDateTime expiresAt) {
        OffsetDateTime created = OffsetDateTime.now().minusSeconds(5);
        return new FxQuote(UUID.randomUUID(), owner, FxDirection.BUY_FOREIGN, "KRW", new BigDecimal("1322750"),
                "USD", new BigDecimal("1000"), new BigDecimal("1300"), new BigDecimal("1322.75"),
                new BigDecimal("0.0175"), BigDecimal.ZERO, created, expiresAt);
    }

    private FxQuote validQuote() {
        return quote(accountId, OffsetDateTime.now().plusSeconds(25));
    }

    private FxConversion conversionOf(FxQuote quote) {
        return FxConversion.of(UUID.randomUUID(), quote, new BigDecimal("1300000"), new BigDecimal("22750"),
                OffsetDateTime.now());
    }

    @Test
    @DisplayName("유효한 견적은 원장을 준비한 뒤 체결하고, 체결 뒤 견적을 지운다")
    void convertsAValidQuoteAndCleansUp() {
        FxQuote quote = validQuote();
        FxConversion conversion = conversionOf(quote);
        when(tradeService.findCompletedTradeId(accountId, "FX_CONVERSION", "key-1")).thenReturn(Optional.empty());
        when(quoteStore.find(quote.quoteId())).thenReturn(Optional.of(quote));
        when(tradeService.executeFxConversion(eq("key-1"), eq(quote), any(OffsetDateTime.class), eq(MONTH)))
                .thenReturn(conversion);

        FxConversion result = facade.convert(accountId, quote.quoteId(), "key-1");

        assertThat(result).isSameAs(conversion);
        InOrder order = inOrder(ledgerResolver, tradeService, quoteStore);
        order.verify(ledgerResolver).resolveOrInitializeLedger(accountId, "KRW", AssetType.FIAT, MONTH);
        order.verify(ledgerResolver).resolveOrInitializeLedger(accountId, "USD", AssetType.FIAT, MONTH);
        order.verify(tradeService).executeFxConversion(eq("key-1"), eq(quote), any(OffsetDateTime.class), eq(MONTH));
        order.verify(quoteStore).delete(quote.quoteId());
    }

    @Test
    @DisplayName("같은 멱등성 키로 이미 체결했으면 견적이 만료됐어도 처음 체결을 돌려준다")
    void replaysTheCompletedConversionWithoutLookingAtTheQuote() {
        FxQuote expired = quote(accountId, OffsetDateTime.now().minusSeconds(60));
        FxConversion existing = conversionOf(expired);
        when(tradeService.findCompletedTradeId(accountId, "FX_CONVERSION", "key-1"))
                .thenReturn(Optional.of(existing.getId()));
        when(conversionRepository.findById(existing.getId())).thenReturn(Optional.of(existing));

        FxConversion result = facade.convert(accountId, expired.quoteId(), "key-1");

        assertThat(result).isSameAs(existing);
        verifyNoInteractions(quoteStore, ledgerResolver);
        verify(tradeService, never()).executeFxConversion(anyString(), any(), any(), anyString());
    }

    @Test
    @DisplayName("없거나 만료된 견적은 거부한다")
    void rejectsAMissingQuote() {
        UUID quoteId = UUID.randomUUID();
        when(tradeService.findCompletedTradeId(accountId, "FX_CONVERSION", "key-1")).thenReturn(Optional.empty());
        when(quoteStore.find(quoteId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> facade.convert(accountId, quoteId, "key-1"))
                .isInstanceOf(FxQuoteExpiredException.class);

        verifyNoInteractions(ledgerResolver);
        verify(tradeService, never()).executeFxConversion(anyString(), any(), any(), anyString());
    }

    @Test
    @DisplayName("저장소에는 남아 있어도 만료 시각이 지난 견적은 거부한다")
    void rejectsAnExpiredQuote() {
        FxQuote expired = quote(accountId, OffsetDateTime.now().minusNanos(1_000));
        when(tradeService.findCompletedTradeId(accountId, "FX_CONVERSION", "key-1")).thenReturn(Optional.empty());
        when(quoteStore.find(expired.quoteId())).thenReturn(Optional.of(expired));

        assertThatThrownBy(() -> facade.convert(accountId, expired.quoteId(), "key-1"))
                .isInstanceOf(FxQuoteExpiredException.class);

        verifyNoInteractions(ledgerResolver);
    }

    @Test
    @DisplayName("다른 계좌의 견적은 없는 견적과 똑같이 거부한다")
    void rejectsAnotherAccountsQuote() {
        FxQuote others = quote(UUID.randomUUID(), OffsetDateTime.now().plusSeconds(25));
        when(tradeService.findCompletedTradeId(accountId, "FX_CONVERSION", "key-1")).thenReturn(Optional.empty());
        when(quoteStore.find(others.quoteId())).thenReturn(Optional.of(others));

        assertThatThrownBy(() -> facade.convert(accountId, others.quoteId(), "key-1"))
                .isInstanceOf(FxQuoteExpiredException.class);

        verifyNoInteractions(ledgerResolver);
        verify(tradeService, never()).executeFxConversion(anyString(), any(), any(), anyString());
    }

    @Test
    @DisplayName("없는 계좌는 거부한다")
    void rejectsAnUnknownAccount() {
        FxQuote quote = validQuote();
        when(tradeService.findCompletedTradeId(accountId, "FX_CONVERSION", "key-1")).thenReturn(Optional.empty());
        when(quoteStore.find(quote.quoteId())).thenReturn(Optional.of(quote));
        when(accountRepository.findById(accountId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> facade.convert(accountId, quote.quoteId(), "key-1"))
                .isInstanceOf(AccountNotFoundException.class);

        verifyNoInteractions(ledgerResolver);
    }

    @Test
    @DisplayName("정지된 계좌는 원장을 만들기 전에 거부한다")
    void rejectsASuspendedAccountBeforeCreatingLedgers() {
        FxQuote quote = validQuote();
        Account suspended = Account.open(accountId, "FX_USER", "KRW");
        suspended.suspend();
        when(tradeService.findCompletedTradeId(accountId, "FX_CONVERSION", "key-1")).thenReturn(Optional.empty());
        when(quoteStore.find(quote.quoteId())).thenReturn(Optional.of(quote));
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(suspended));

        assertThatThrownBy(() -> facade.convert(accountId, quote.quoteId(), "key-1"))
                .isInstanceOf(InvalidAccountStateException.class);

        verifyNoInteractions(ledgerResolver);
    }

    @Test
    @DisplayName("견적 저장소 장애는 만료로 둔갑시키지 않고 그대로 올린다")
    void propagatesQuoteStoreFailures() {
        UUID quoteId = UUID.randomUUID();
        when(tradeService.findCompletedTradeId(accountId, "FX_CONVERSION", "key-1")).thenReturn(Optional.empty());
        when(quoteStore.find(quoteId)).thenThrow(new FxQuoteUnavailableException("down", new RuntimeException()));

        assertThatThrownBy(() -> facade.convert(accountId, quoteId, "key-1"))
                .isInstanceOf(FxQuoteUnavailableException.class);
    }

    @Test
    @DisplayName("체결이 실패하면 견적을 지우지 않는다 (고객이 같은 견적으로 다시 시도할 수 있다)")
    void keepsTheQuoteWhenTheConversionFails() {
        FxQuote quote = validQuote();
        when(tradeService.findCompletedTradeId(accountId, "FX_CONVERSION", "key-1")).thenReturn(Optional.empty());
        when(quoteStore.find(quote.quoteId())).thenReturn(Optional.of(quote));
        when(tradeService.executeFxConversion(anyString(), any(), any(), anyString()))
                .thenThrow(new FxQuoteAlreadyUsedException("used"));

        assertThatThrownBy(() -> facade.convert(accountId, quote.quoteId(), "key-1"))
                .isInstanceOf(FxQuoteAlreadyUsedException.class);

        verify(quoteStore, never()).delete(any());
    }

    @Test
    @DisplayName("체결 뒤 견적 정리에 실패해도 체결은 성공으로 돌려준다")
    void aFailedCleanupDoesNotFailTheConversion() {
        FxQuote quote = validQuote();
        FxConversion conversion = conversionOf(quote);
        when(tradeService.findCompletedTradeId(accountId, "FX_CONVERSION", "key-1")).thenReturn(Optional.empty());
        when(quoteStore.find(quote.quoteId())).thenReturn(Optional.of(quote));
        when(tradeService.executeFxConversion(anyString(), any(), any(), anyString())).thenReturn(conversion);
        doThrow(new FxQuoteUnavailableException("down", new RuntimeException())).when(quoteStore).delete(quote.quoteId());

        assertThat(facade.convert(accountId, quote.quoteId(), "key-1")).isSameAs(conversion);
    }
}
