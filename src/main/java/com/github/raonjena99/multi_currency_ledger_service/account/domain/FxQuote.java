package com.github.raonjena99.multi_currency_ledger_service.account.domain;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 환전 견적입니다. 시세와 스프레드, 우대율을 적용한 고객 환율과 받을 금액을 유효 시간 동안 고정합니다.
 *
 * <p>견적을 받은 뒤 시장이 움직여도 체결은 이 견적의 환율로 이뤄집니다. 체결 때 환율을 다시 조회하지 않는 것이
 * 견적의 의미이고, 그 위험은 회사가 지며 스프레드가 그 대가입니다. 그래서 유효 시간은 짧게 둡니다.
 *
 * @param quoteId          견적 ID. 체결 요청이 이 값으로 견적을 가리킨다
 * @param accountId        견적을 받은 계좌. 체결할 때 같은 계좌여야 한다
 * @param direction        환전 방향
 * @param fromCurrency     고객이 내는 통화
 * @param fromAmount       고객이 내는 금액 (통화의 최소 단위에 맞춘 값)
 * @param toCurrency       고객이 받는 통화
 * @param toAmount         고객이 받을 금액. 받는 통화의 최소 단위로 내림한 값
 * @param marketRate       시세. 1 외화 = 기준 통화 얼마
 * @param customerRate     고객에게 적용되는 환율. 1 외화 = 기준 통화 얼마
 * @param spreadRate       통화별로 설정한 스프레드
 * @param preferentialRate 적용한 우대율
 * @param createdAt        견적 발급 시각
 * @param expiresAt        견적 만료 시각
 */
public record FxQuote(
        UUID quoteId,
        UUID accountId,
        FxDirection direction,
        String fromCurrency,
        BigDecimal fromAmount,
        String toCurrency,
        BigDecimal toAmount,
        BigDecimal marketRate,
        BigDecimal customerRate,
        BigDecimal spreadRate,
        BigDecimal preferentialRate,
        OffsetDateTime createdAt,
        OffsetDateTime expiresAt) {

    /** 계좌의 기준 통화. 환전은 항상 기준 통화와 외화 사이에서 일어난다. */
    public String baseCurrency() {
        return direction == FxDirection.BUY_FOREIGN ? fromCurrency : toCurrency;
    }

    /** 외화. */
    public String foreignCurrency() {
        return direction == FxDirection.BUY_FOREIGN ? toCurrency : fromCurrency;
    }

    /** 외화 금액. 한도는 이 금액에 적용된다. */
    public BigDecimal foreignAmount() {
        return direction == FxDirection.BUY_FOREIGN ? toAmount : fromAmount;
    }

    /** 지정한 시각에 이미 만료되었는지 여부. 만료 시각 정각부터 만료로 본다. */
    public boolean isExpiredAt(OffsetDateTime now) {
        return !now.isBefore(expiresAt);
    }
}
