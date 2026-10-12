package com.github.raonjena99.multi_currency_ledger_service.transaction.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.springframework.stereotype.Service;

import com.github.raonjena99.multi_currency_ledger_service.common.domain.CurrencyScaleResolver;
import com.github.raonjena99.multi_currency_ledger_service.common.model.AssetType;
import com.github.raonjena99.multi_currency_ledger_service.common.port.ExchangeRateProvider;
import com.github.raonjena99.multi_currency_ledger_service.common.port.ExchangeRateProvider.ExchangeRate;
import com.github.raonjena99.multi_currency_ledger_service.transaction.infrastructure.query.FxPositionDao;
import com.github.raonjena99.multi_currency_ledger_service.transaction.infrastructure.query.FxPositionDao.PositionRow;
import com.github.raonjena99.multi_currency_ledger_service.transaction.infrastructure.query.FxPositionDao.SpreadRevenueRow;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 회사 외화 포지션을 현재 시세로 재평가해 보여 줍니다. 원장에는 아무것도 기록하지 않는 조회입니다.
 *
 * <p>환전이 일어나면 회사는 고객의 반대편이 됩니다. 고객이 달러를 사면 회사는 달러를 판 포지션(음수)을 갖고, 이후 달러가 오르면
 * 손실입니다. 스프레드는 이 환리스크에 대한 대가이므로, 노출 규모와 현재 손익을 함께 봐야 그 대가가 충분한지 판단할 수 있습니다.
 *
 * <p><b>기준 통화별로 묶는 이유.</b> 고객 계좌의 기준 통화가 여럿일 수 있고, 분개의 금액은 그 거래의 기준 통화로 저장됩니다.
 * 서로 다른 기준 통화의 금액을 더하려면 거래 시점 환율이 필요한데 그 값은 남아 있지 않습니다. 그래서 장부가와 손익은 기준
 * 통화별로 따로 계산하고, 통화별 순수량(노출)만 모든 장부를 합산해 따로 보여 줍니다.
 *
 * <p><b>합산 손익의 항등식.</b> 환전 한 건에서 회사 FX 계정의 차변 − 대변은 그 건의 스프레드 수익과 같습니다. 그래서 한 기준
 * 통화의 장부가 합계는 스프레드 수익이고, 합산 손익(스프레드 수익 + 환손익)은 포지션 시장가치의 합과 같습니다.
 *
 * <p><b>환손익은 실현과 미실현을 나누지 않습니다.</b> 회사 포지션은 평균 단가를 따로 추적하지 않으므로 닫힌 포지션의 손익도
 * 장부가에 남아 현재 시세 기준 손익에 합산됩니다(수량 0 인 포지션의 환손익이 그 예입니다).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FxPositionService {

    private final FxPositionDao positionDao;
    private final ExchangeRateProvider exchangeRateProvider;

    /**
     * 회사 외화 포지션 보고서.
     *
     * @param asOf      재평가 기준 시각
     * @param exposures 통화별 순포지션(모든 기준 통화의 장부를 합산한 순수량). 음수는 회사가 판 포지션
     * @param books     기준 통화별 장부와 재평가
     */
    public record FxPositionReport(OffsetDateTime asOf, List<CurrencyExposure> exposures, List<BaseCurrencyBook> books) {}

    /**
     * 통화별 순포지션.
     *
     * @param currency    통화
     * @param netQuantity 순수량(차변 − 대변). 음수는 회사가 외화를 내준(판) 포지션
     */
    public record CurrencyExposure(String currency, BigDecimal netQuantity) {}

    /**
     * 한 기준 통화로 묶은 장부.
     *
     * @param baseCurrency  고객 계좌의 기준 통화. 이 장부의 금액은 모두 이 통화다
     * @param spreadRevenue 누적 스프레드 수익
     * @param positions     통화별 포지션
     * @param fxResult      환손익의 합(현재 시세 기준, 실현·미실현 합산). 시세를 구하지 못한 포지션이 있으면 null
     * @param totalResult   합산 손익 = 스프레드 수익 + 환손익 = 포지션 시장가치의 합. 환손익이 null 이면 null
     */
    public record BaseCurrencyBook(String baseCurrency, BigDecimal spreadRevenue, List<PositionView> positions,
                                   BigDecimal fxResult, BigDecimal totalResult) {}

    /**
     * 한 통화의 포지션과 재평가.
     *
     * @param currency    통화
     * @param netQuantity 순수량(차변 − 대변)
     * @param bookValue   장부가(기준 통화). 거래 시점의 시세 가치를 같은 부호 규칙으로 합한 금액
     * @param marketRate  현재 시세(1 단위 = 기준 통화 얼마). 시세를 구하지 못했으면 null
     * @param stale       시세가 지연 데이터인지 여부
     * @param marketValue 현재 시장가치 = 순수량 × 시세(기준 통화 최소 단위로 반올림). 시세를 구하지 못했으면 null
     * @param fxResult    환손익 = 시장가치 − 장부가. 시장가치가 null 이면 null
     */
    public record PositionView(String currency, BigDecimal netQuantity, BigDecimal bookValue, BigDecimal marketRate,
                               boolean stale, BigDecimal marketValue, BigDecimal fxResult) {}

    /**
     * 현재 시세로 재평가한 회사 외화 포지션을 구합니다.
     */
    public FxPositionReport report() {
        OffsetDateTime asOf = OffsetDateTime.now();
        List<PositionRow> rows = positionDao.findPositions();
        Map<String, BigDecimal> revenueByBase = new TreeMap<>();
        for (SpreadRevenueRow revenue : positionDao.findSpreadRevenue()) {
            revenueByBase.put(revenue.baseCurrency(), revenue.revenue());
        }

        // 기준 통화별 장부 (통화 코드 순)
        Map<String, List<PositionRow>> rowsByBase = new TreeMap<>();
        Map<String, BigDecimal> exposureByCurrency = new TreeMap<>();
        for (PositionRow row : rows) {
            rowsByBase.computeIfAbsent(row.baseCurrency(), base -> new ArrayList<>()).add(row);
            exposureByCurrency.merge(row.currency(), row.netQuantity(), BigDecimal::add);
        }

        List<BaseCurrencyBook> books = new ArrayList<>();
        rowsByBase.forEach((base, baseRows) -> books.add(
                valueBook(base, baseRows, revenueByBase.getOrDefault(base, BigDecimal.ZERO))));

        List<CurrencyExposure> exposures = new ArrayList<>();
        exposureByCurrency.forEach((currency, quantity) -> exposures.add(new CurrencyExposure(currency, quantity)));
        return new FxPositionReport(asOf, exposures, books);
    }

    private BaseCurrencyBook valueBook(String baseCurrency, List<PositionRow> rows, BigDecimal spreadRevenue) {
        int scale = CurrencyScaleResolver.resolveScale(AssetType.FIAT, baseCurrency);
        List<PositionView> positions = new ArrayList<>();
        BigDecimal fxResult = BigDecimal.ZERO;
        boolean fullyValued = true;

        for (PositionRow row : rows) {
            PositionView view = valuePosition(row, baseCurrency, scale);
            positions.add(view);
            if (view.fxResult() == null) {
                fullyValued = false;
            } else {
                fxResult = fxResult.add(view.fxResult());
            }
        }

        BigDecimal bookFxResult = fullyValued ? fxResult : null;
        BigDecimal totalResult = fullyValued ? spreadRevenue.add(fxResult) : null;
        return new BaseCurrencyBook(baseCurrency, spreadRevenue, positions, bookFxResult, totalResult);
    }

    private PositionView valuePosition(PositionRow row, String baseCurrency, int scale) {
        BigDecimal quantity = row.netQuantity();
        if (row.currency().equals(baseCurrency)) {
            // 기준 통화는 시세 1. 환손익이 없다.
            BigDecimal value = quantity.setScale(scale, RoundingMode.HALF_EVEN);
            return new PositionView(row.currency(), quantity, row.bookValue(), BigDecimal.ONE, false, value,
                    value.subtract(row.bookValue()));
        }

        ExchangeRate rate = lookup(row.currency(), baseCurrency);
        if (rate == null) {
            // 시세를 모르면 평가를 비운다. 다만 수량이 0 이면 시세와 상관없이 가치는 0 이다.
            BigDecimal value = quantity.signum() == 0 ? BigDecimal.ZERO.setScale(scale) : null;
            return new PositionView(row.currency(), quantity, row.bookValue(), null, false, value,
                    value == null ? null : value.subtract(row.bookValue()));
        }
        BigDecimal value = quantity.multiply(rate.rate()).setScale(scale, RoundingMode.HALF_EVEN);
        return new PositionView(row.currency(), quantity, row.bookValue(), rate.rate(), rate.isStale(), value,
                value.subtract(row.bookValue()));
    }

    /** 시세를 구하지 못하면 null. 한 통화의 시세 장애가 나머지 포지션의 조회까지 막지 않게 한다. */
    private ExchangeRate lookup(String currency, String baseCurrency) {
        try {
            return exchangeRateProvider.getExchangeRate(currency, baseCurrency);
        } catch (RuntimeException e) {
            log.warn("회사 외화 포지션 재평가용 시세를 구하지 못했습니다. {} → {}: {}", currency, baseCurrency, e.getMessage());
            return null;
        }
    }
}
