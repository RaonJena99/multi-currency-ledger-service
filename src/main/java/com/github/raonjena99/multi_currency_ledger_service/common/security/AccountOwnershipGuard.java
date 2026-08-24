package com.github.raonjena99.multi_currency_ledger_service.common.security;

import java.util.UUID;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * 요청된 계좌가 인증 주체의 소유인지 검증합니다.
 *
 * <p>이 검증이 없으면 계좌 ID 를 경로에 담아 보내는 것만으로 <b>남의 계좌를 거래</b>할 수 있습니다.
 * 인증만 붙이고 소유권을 확인하지 않는 것은 인증이 없는 것과 사실상 같습니다.
 */
@Slf4j
@Component
public class AccountOwnershipGuard {

    /**
     * 현재 인증 주체가 대상 계좌를 조작할 수 있는지 확인합니다.
     * 관리자 권한 보유자는 모든 계좌에 접근할 수 있습니다.
     *
     * @param accountId 요청 대상 계좌 ID
     * @throws AccessDeniedException 주체가 없거나 계좌 소유자가 아닌 경우
     */
    public void requireOwnership(UUID accountId) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (authentication == null || !(authentication.getPrincipal() instanceof LedgerPrincipal principal)) {
            throw new AccessDeniedException("Authentication is required to access account resources.");
        }

        if (principal.admin()) {
            return;
        }

        if (principal.accountId() == null || !principal.accountId().equals(accountId)) {
            log.warn("계좌 소유권 검증 실패. subject={}, requested={}", principal.subject(), accountId);
            throw new AccessDeniedException("You are not allowed to access this account.");
        }
    }
}
