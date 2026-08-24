package com.github.raonjena99.multi_currency_ledger_service.account;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.github.raonjena99.multi_currency_ledger_service.common.domain.Money;

public interface AccountApi {
    /**
     * 계좌의 기준 통화(Base Currency)를 반환합니다.
     */
    String getBaseCurrency(UUID accountId);

    /**
     * 계좌의 모든 자산별 실시간 최신 잔고를 반환합니다.
     */
    List<AccountBalanceDto> getBalances(UUID accountId);

    /**
     * 거래 경로가 아닌 보정(대사 수수료 조정 등)으로 계좌의 법정화폐 잔고를 조정합니다.
     *
     * <p>양수는 고객 잔고에 입금(환불), 음수는 출금(추가 징수)입니다. 호출자의 트랜잭션에
     * 참여하므로, 근거가 되는 분개 기록과 같은 트랜잭션 안에서 호출하면 분개와 잔고가
     * 원자적으로 함께 반영됩니다.
     *
     * @param accountId    조정 대상 계좌 ID
     * @param adjustment   조정 금액 (양수 = 입금, 음수 = 출금)
     * @param transactedAt 보정의 근거 거래 시각. 실효 원장 월 결정에 사용됩니다.
     */
    void applyFiatBalanceAdjustment(UUID accountId, Money adjustment, OffsetDateTime transactedAt);

    record AccountBalanceDto(String assetCode, BigDecimal totalQuantity, BigDecimal avgUnitPrice, String quoteCurrency) {}
}
