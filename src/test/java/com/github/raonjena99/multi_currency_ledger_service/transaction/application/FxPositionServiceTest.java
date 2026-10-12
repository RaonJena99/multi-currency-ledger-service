package com.github.raonjena99.multi_currency_ledger_service.transaction.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.github.raonjena99.multi_currency_ledger_service.common.exception.MarketDataUnavailableException;
import com.github.raonjena99.multi_currency_ledger_service.common.port.ExchangeRateProvider;
import com.github.raonjena99.multi_currency_ledger_service.common.port.ExchangeRateProvider.ExchangeRate;
import com.github.raonjena99.multi_currency_ledger_service.transaction.application.FxPositionService.BaseCurrencyBook;
import com.github.raonjena99.multi_currency_ledger_service.transaction.application.FxPositionService.CurrencyExposure;
import com.github.raonjena99.multi_currency_ledger_service.transaction.application.FxPositionService.FxPositionReport;
import com.github.raonjena99.multi_currency_ledger_service.transaction.application.FxPositionService.PositionView;
import com.github.raonjena99.multi_currency_ledger_service.transaction.infrastructure.query.FxPositionDao;
import com.github.raonjena99.multi_currency_ledger_service.transaction.infrastructure.query.FxPositionDao.PositionRow;
import com.github.raonjena99.multi_currency_ledger_service.transaction.infrastructure.query.FxPositionDao.SpreadRevenueRow;

/**
 * 회사 외화 포지션 재평가를 검증합니다.
 *
 * <p>핵심 항등식: 기준 통화별 합산 손익(스프레드 수익 + 환손익)은 포지션 시장가치의 합과 같습니다. 환전 한 건에서 회사 FX
 * 계정의 차변 − 대변이 그 건의 스프레드 수익이라 장부가의 합이 스프레드 수익과 같기 때문입니다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("단위 테스트: 회사 외화 포지션 재평가")
class FxPositionServiceTest {

    @Mock private FxPositionDao dao;
    @Mock private ExchangeRateProvider rates;

    @InjectMocks private FxPositionService service;

    private static PositionRow row(String currency, String base, String quantity, String book) {
        return new PositionRow(currency, base, new BigDecimal(quantity), new BigDecimal(book));
    }

    private static SpreadRevenueRow spread(String base, String revenue) {
        return new SpreadRevenueRow(base, new BigDecimal(revenue));
    }

    private void rate(String from, String to, String rate, boolean stale) {
        when(rates.getExchangeRate(from, to)).thenReturn(new ExchangeRate(new BigDecimal(rate), stale));
    }

    private static PositionView position(BaseCurrencyBook book, String currency) {
        return book.positions().stream().filter(p -> p.currency().equals(currency)).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("달러를 판 포지션은 시세가 오르면 환손실이고, 합산 손익은 스프레드 수익을 더한 값이다")
    void aShortPositionLosesWhenTheForeignCurrencyRises() {
        // 고객이 1,322,750 원으로 USD 1,000 을 샀다: 회사는 USD 1,000 을 시세 1,300,000 원에 내주고 스프레드 22,750 을 번다.
        when(dao.findPositions()).thenReturn(List.of(
                row("KRW", "KRW", "1322750", "1322750"), row("USD", "KRW", "-1000", "-1300000")));
        when(dao.findSpreadRevenue()).thenReturn(List.of(spread("KRW", "22750")));
        rate("USD", "KRW", "1400", false);

        FxPositionReport report = service.report();

        assertThat(report.books()).singleElement().satisfies(book -> {
            assertThat(book.baseCurrency()).isEqualTo("KRW");
            assertThat(book.spreadRevenue()).isEqualByComparingTo("22750");
            PositionView usd = position(book, "USD");
            assertThat(usd.netQuantity()).isEqualByComparingTo("-1000");
            assertThat(usd.marketRate()).isEqualByComparingTo("1400");
            assertThat(usd.marketValue()).isEqualByComparingTo("-1400000");
            assertThat(usd.fxResult()).as("1,300,000 에 팔았는데 지금 1,400,000").isEqualByComparingTo("-100000");
            assertThat(usd.stale()).isFalse();
            assertThat(book.fxResult()).isEqualByComparingTo("-100000");
            assertThat(book.totalResult()).isEqualByComparingTo("-77250");
        });
    }

    @Test
    @DisplayName("기준 통화 포지션은 시세 1 로 평가해 손익이 없고 시세를 조회하지 않는다")
    void theBaseCurrencyPositionNeedsNoRate() {
        when(dao.findPositions()).thenReturn(List.of(row("KRW", "KRW", "1322750", "1322750")));
        when(dao.findSpreadRevenue()).thenReturn(List.of());

        PositionView krw = position(service.report().books().get(0), "KRW");

        assertThat(krw.marketRate()).isEqualByComparingTo("1");
        assertThat(krw.marketValue()).isEqualByComparingTo("1322750");
        assertThat(krw.fxResult()).isEqualByComparingTo("0");
        org.mockito.Mockito.verifyNoInteractions(rates);
    }

    @Test
    @DisplayName("합산 손익은 포지션 시장가치의 합과 같다 (왕복해서 달러가 0 이 된 경우도)")
    void totalResultEqualsTheSumOfMarketValues() {
        // 1,300 에 팔고 1,400 에 되샀다: 달러는 0, 회사는 원화를 52,750 순지급했고 스프레드 47,250 을 벌었다.
        when(dao.findPositions()).thenReturn(List.of(
                row("KRW", "KRW", "-52750", "-52750"), row("USD", "KRW", "0", "100000")));
        when(dao.findSpreadRevenue()).thenReturn(List.of(spread("KRW", "47250")));
        rate("USD", "KRW", "1450", false);

        BaseCurrencyBook book = service.report().books().get(0);

        BigDecimal marketValueTotal = book.positions().stream().map(PositionView::marketValue)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(book.totalResult()).isEqualByComparingTo("-52750");
        assertThat(book.totalResult()).as("항등식").isEqualByComparingTo(marketValueTotal);
        assertThat(position(book, "USD").fxResult()).as("닫힌 포지션의 손익이 장부가에 남아 있다").isEqualByComparingTo("-100000");
    }

    @Test
    @DisplayName("낡은 시세는 표시하되 낡았다고 알린다")
    void aStaleRateIsFlagged() {
        when(dao.findPositions()).thenReturn(List.of(row("USD", "KRW", "-1000", "-1300000")));
        when(dao.findSpreadRevenue()).thenReturn(List.of());
        rate("USD", "KRW", "1400", true);

        PositionView usd = position(service.report().books().get(0), "USD");

        assertThat(usd.stale()).isTrue();
        assertThat(usd.marketValue()).isEqualByComparingTo("-1400000");
    }

    @Test
    @DisplayName("시세를 구하지 못한 통화는 평가를 비우고 합산 손익도 비우되 나머지는 보여 준다")
    void anUnavailableRateLeavesTheValuationEmpty() {
        when(dao.findPositions()).thenReturn(List.of(
                row("KRW", "KRW", "1322750", "1322750"), row("USD", "KRW", "-1000", "-1300000")));
        when(dao.findSpreadRevenue()).thenReturn(List.of(spread("KRW", "22750")));
        when(rates.getExchangeRate("USD", "KRW")).thenThrow(new MarketDataUnavailableException("down"));

        BaseCurrencyBook book = service.report().books().get(0);

        PositionView usd = position(book, "USD");
        assertThat(usd.marketRate()).isNull();
        assertThat(usd.marketValue()).isNull();
        assertThat(usd.fxResult()).isNull();
        assertThat(usd.netQuantity()).as("수량과 장부가는 시세 없이도 보인다").isEqualByComparingTo("-1000");
        assertThat(position(book, "KRW").marketValue()).isEqualByComparingTo("1322750");
        assertThat(book.spreadRevenue()).isEqualByComparingTo("22750");
        assertThat(book.fxResult()).isNull();
        assertThat(book.totalResult()).isNull();
    }

    @Test
    @DisplayName("수량이 0 인 포지션은 시세를 구하지 못해도 시장가치 0 으로 평가한다")
    void aFlatPositionIsWorthZeroEvenWithoutARate() {
        when(dao.findPositions()).thenReturn(List.of(row("USD", "KRW", "0", "100000")));
        when(dao.findSpreadRevenue()).thenReturn(List.of(spread("KRW", "47250")));
        when(rates.getExchangeRate("USD", "KRW")).thenThrow(new MarketDataUnavailableException("down"));

        BaseCurrencyBook book = service.report().books().get(0);

        assertThat(position(book, "USD").marketValue()).isEqualByComparingTo("0");
        assertThat(book.totalResult()).isEqualByComparingTo("-52750");
    }

    @Test
    @DisplayName("고객 계좌의 기준 통화가 여럿이면 기준 통화별 장부로 나누고, 통화별 순포지션은 합산한다")
    void groupsBooksByBaseCurrencyAndAggregatesExposures() {
        when(dao.findPositions()).thenReturn(List.of(
                row("KRW", "KRW", "1322750", "1322750"), row("USD", "KRW", "-1000", "-1300000"),
                row("KRW", "USD", "-1310319", "-982.73"), row("USD", "USD", "1000.00", "1000.00")));
        when(dao.findSpreadRevenue()).thenReturn(List.of(spread("KRW", "22750"), spread("USD", "17.27")));
        rate("USD", "KRW", "1300", false);
        rate("KRW", "USD", "0.00075", false);

        FxPositionReport report = service.report();

        assertThat(report.books()).extracting(BaseCurrencyBook::baseCurrency).containsExactly("KRW", "USD");
        // KRW 포지션: 원화 기준 장부에서 +1,322,750, 달러 기준 장부에서 −1,310,319 → 합산 +12,431
        assertThat(report.exposures()).extracting(CurrencyExposure::currency).containsExactly("KRW", "USD");
        assertThat(report.exposures().get(0).netQuantity()).isEqualByComparingTo("12431");
        // USD 포지션: 원화 기준 장부에서 −1,000, 달러 기준 장부에서 +1,000.00 → 합산 0
        assertThat(report.exposures().get(1).netQuantity()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("시장가치는 기준 통화의 최소 단위로 반올림해 보여 준다")
    void marketValueIsRoundedToTheBaseCurrencyScale() {
        when(dao.findPositions()).thenReturn(List.of(row("KRW", "USD", "-1310319", "-982.73")));
        when(dao.findSpreadRevenue()).thenReturn(List.of(spread("USD", "17.27")));
        rate("KRW", "USD", "0.00075", false);

        PositionView krw = position(service.report().books().get(0), "KRW");

        // −1,310,319 × 0.00075 = −982.73925 → 달러는 소수 2자리
        assertThat(krw.marketValue()).isEqualByComparingTo("-982.74");
        assertThat(krw.fxResult()).isEqualByComparingTo("-0.01");
    }

    @Test
    @DisplayName("환전이 없으면 비어 있다")
    void emptyWhenThereAreNoPositions() {
        when(dao.findPositions()).thenReturn(List.of());
        when(dao.findSpreadRevenue()).thenReturn(List.of());

        FxPositionReport report = service.report();

        assertThat(report.books()).isEmpty();
        assertThat(report.exposures()).isEmpty();
        assertThat(report.asOf()).isNotNull();
    }

    @Test
    @DisplayName("스프레드 수익 행이 없는 기준 통화는 수익 0 으로 본다")
    void missingSpreadRevenueIsZero() {
        when(dao.findPositions()).thenReturn(List.of(row("KRW", "KRW", "100", "100")));
        when(dao.findSpreadRevenue()).thenReturn(List.of());

        assertThat(service.report().books().get(0).spreadRevenue()).isEqualByComparingTo("0");
    }
}
