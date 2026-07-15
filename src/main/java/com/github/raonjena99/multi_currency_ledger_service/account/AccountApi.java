package com.github.raonjena99.multi_currency_ledger_service.account;

import java.util.UUID;

public interface AccountApi {
    /**
     * 계좌의 기준 통화(Base Currency)를 반환합니다.
     */
    String getBaseCurrency(UUID accountId);
}
