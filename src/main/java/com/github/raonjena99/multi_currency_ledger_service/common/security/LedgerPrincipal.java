package com.github.raonjena99.multi_currency_ledger_service.common.security;

import java.util.UUID;

/**
 * 인증된 호출자를 나타내는 주체(principal)입니다.
 *
 * @param subject   외부 인증 시스템이 부여한 주체 식별자
 * @param accountId 이 주체가 소유한 계좌 ID. 관리자처럼 계좌가 없는 주체는 null 일 수 있습니다.
 * @param admin     백오피스 권한 보유 여부
 */
public record LedgerPrincipal(String subject, UUID accountId, boolean admin) {

    /** Spring Security 권한 이름 규약 */
    public static final String ROLE_CUSTOMER = "ROLE_CUSTOMER";
    public static final String ROLE_ADMIN = "ROLE_ADMIN";
}
