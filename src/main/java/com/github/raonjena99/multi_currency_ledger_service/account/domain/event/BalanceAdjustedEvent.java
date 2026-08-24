package com.github.raonjena99.multi_currency_ledger_service.account.domain.event;

import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

import com.github.raonjena99.multi_currency_ledger_service.common.domain.Money;

/**
 * BalanceAdjustedEvent 레코드.
 * 거래(매수/매도)가 아닌 경로 — 대사 수수료 보정(FEE_ADJUSTMENT) 등 — 로 계좌 잔고가
 * 변경되었음을 알리는 도메인 이벤트입니다.
 *
 * <p>{@link TradeExecutedEvent} 와 달리 이 이벤트는 원장 기록 커맨드로 변환되지 않습니다.
 * 잔고 변경의 근거 분개는 이미 원장에 존재하며(보정 커맨드가 분개와 잔고를 한 트랜잭션에서
 * 기록), 이 이벤트의 소비자는 파생 뷰(포트폴리오 캐시 등) 갱신만 담당합니다.
 *
 * @param accountId  잔고가 조정된 계좌 ID
 * @param adjustment 조정 금액. 양수는 입금(환불), 음수는 출금(추가 징수)
 * @param occurredAt 이벤트 발생 시각
 */
public record BalanceAdjustedEvent(
    UUID accountId,
    Money adjustment,
    OffsetDateTime occurredAt
) {
    public BalanceAdjustedEvent {
        Objects.requireNonNull(accountId, "Account ID cannot be null");
        Objects.requireNonNull(adjustment, "Adjustment cannot be null");
        if (occurredAt == null) {
            occurredAt = OffsetDateTime.now();
        }
    }
}
