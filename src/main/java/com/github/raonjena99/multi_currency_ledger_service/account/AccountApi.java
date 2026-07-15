package com.github.raonjena99.multi_currency_ledger_service.account;

import java.util.UUID;

public interface AccountApi {
    /**
     * 계좌의 기준 통화(Base Currency)를 반환합니다.
     */
    String getBaseCurrency(UUID accountId);

    /**
     * 계좌의 모든 자산별 실시간 최신 잔고를 반환합니다.
     */
    java.util.List<AccountBalanceDto> getBalances(UUID accountId);

    record AccountBalanceDto(String assetCode, java.math.BigDecimal totalQuantity, java.math.BigDecimal avgUnitPrice, String quoteCurrency) {}
}
