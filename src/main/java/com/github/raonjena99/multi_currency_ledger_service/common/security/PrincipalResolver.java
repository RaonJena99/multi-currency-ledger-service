package com.github.raonjena99.multi_currency_ledger_service.common.security;

import jakarta.servlet.http.HttpServletRequest;

/**
 * HTTP 요청에서 인증 주체를 해석하는 확장 지점입니다.
 *
 * <p>토큰 발급 서버는 이 서비스의 범위가 아닙니다. 이 인터페이스만 교체하면 JWT 검증,
 * OAuth2 리소스 서버, 게이트웨이가 주입한 헤더 등 어떤 방식으로도 연결할 수 있습니다.
 */
public interface PrincipalResolver {

    /**
     * 요청에서 인증 주체를 해석합니다.
     *
     * @param request 들어온 HTTP 요청
     * @return 해석된 주체. 인증 정보가 없으면 null 을 반환해 익명 요청으로 처리합니다.
     */
    LedgerPrincipal resolve(HttpServletRequest request);
}
