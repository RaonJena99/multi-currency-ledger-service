package com.github.raonjena99.multi_currency_ledger_service.transaction.application.command;

import java.math.BigDecimal;

/**
 * 환전({@code FX_CONVERSION}) 분개에만 필요한 금액입니다. 계좌 모듈이 견적과 체결 결과로 정해 보냅니다.
 *
 * <p>원장은 환율을 다시 계산하지 않고 이 금액을 그대로 분개합니다. 환전은 기준 통화와 외화 사이에서만 일어나므로
 * 외화 금액은 커맨드의 {@code quantity}(통화는 {@code assetCode}), 기준 통화는 {@code baseCurrency} 입니다.
 *
 * @param direction   {@code BUY_FOREIGN}(기준 통화를 내고 외화를 받음) 또는 {@code SELL_FOREIGN}(외화를 내고 기준 통화를 받음)
 * @param baseAmount  고객이 내거나 받는 <b>기준 통화</b> 금액. 매수는 낸 금액이라 외화의 취득 원가가 되고, 매도는 받은 금액이라
 *                    매각 대금이 된다
 * @param marketValue 외화 금액의 <b>시세</b> 가치(기준 통화). 회사의 외화 포지션이 이 금액으로 기록되고, 고객 금액과의
 *                    차이가 스프레드 수익이다. 회사에 유리한 방향으로 반올림해 수익이 음수가 되지 않는다
 */
public record FxConversionDetail(String direction, BigDecimal baseAmount, BigDecimal marketValue) {
}
