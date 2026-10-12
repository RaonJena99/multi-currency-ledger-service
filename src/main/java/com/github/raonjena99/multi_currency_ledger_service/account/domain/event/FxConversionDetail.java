package com.github.raonjena99.multi_currency_ledger_service.account.domain.event;

import java.math.BigDecimal;
import java.util.Objects;

import com.github.raonjena99.multi_currency_ledger_service.account.domain.FxDirection;

/**
 * 환전({@code FX_CONVERSION}) 이벤트에만 실리는 분개 금액입니다. 원장이 환율을 다시 계산하지 않고 이 금액을 그대로
 * 분개합니다.
 *
 * <p>환전은 계좌의 기준 통화와 외화 사이에서만 일어납니다. 외화 금액은 이벤트의 {@code quantity}(통화는 {@code assetCode}),
 * 기준 통화는 {@code baseCurrency} 입니다.
 *
 * @param direction   환전 방향
 * @param baseAmount  고객이 내거나 받는 <b>기준 통화</b> 금액. 외화 매수는 낸 금액(외화의 취득 원가), 매도는 받은 금액(매각 대금)
 * @param marketValue 외화 금액의 시세 가치(기준 통화). 회사가 반대편 포지션을 이 금액으로 갖고, 고객 금액과의 차이가
 *                    스프레드 수익이 된다. 회사에 유리한 방향으로 반올림해 수익이 음수가 되지 않는다
 */
public record FxConversionDetail(FxDirection direction, BigDecimal baseAmount, BigDecimal marketValue) {

    public FxConversionDetail {
        Objects.requireNonNull(direction, "Direction cannot be null");
        Objects.requireNonNull(baseAmount, "Base amount cannot be null");
        Objects.requireNonNull(marketValue, "Market value cannot be null");
    }
}
