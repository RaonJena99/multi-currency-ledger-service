package com.github.raonjena99.multi_currency_ledger_service.common.security;

import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;

/**
 * 신뢰 경계(API 게이트웨이 등)가 검증 후 주입한 헤더를 읽어 주체를 구성하는 기본 구현체입니다.
 *
 * <p>토큰 자체를 검증하지는 않습니다. 이 서비스는 토큰 발급·검증 주체가 아니며, 게이트웨이 뒤에
 * 배치되는 것을 전제로 합니다. JWT 를 직접 검증해야 한다면 {@link PrincipalResolver} 를
 * 구현한 빈을 하나 등록하면 이 구현체는 물러납니다.
 *
 * <p><b>운영 주의</b>: 이 헤더는 외부에서 직접 도달할 수 없어야 합니다. 게이트웨이가 클라이언트가
 * 보낸 동일 헤더를 반드시 제거(strip)하도록 설정하십시오.
 */
@Slf4j
@Component
@ConditionalOnMissingBean(ignored = HeaderPrincipalResolver.class, value = PrincipalResolver.class)
public class HeaderPrincipalResolver implements PrincipalResolver {

    public static final String SUBJECT_HEADER = "X-Auth-Subject";
    public static final String ACCOUNT_HEADER = "X-Auth-Account-Id";
    public static final String ROLES_HEADER = "X-Auth-Roles";

    @Override
    public LedgerPrincipal resolve(HttpServletRequest request) {
        String subject = request.getHeader(SUBJECT_HEADER);
        if (!StringUtils.hasText(subject)) {
            return null;
        }

        UUID accountId = null;
        String rawAccountId = request.getHeader(ACCOUNT_HEADER);
        if (StringUtils.hasText(rawAccountId)) {
            try {
                accountId = UUID.fromString(rawAccountId);
            } catch (IllegalArgumentException e) {
                log.warn("인증 헤더의 계좌 ID 형식이 올바르지 않습니다. 계좌 없는 주체로 처리합니다.");
            }
        }

        String roles = request.getHeader(ROLES_HEADER);
        boolean admin = roles != null && roles.toUpperCase().contains("ADMIN");

        return new LedgerPrincipal(subject, accountId, admin);
    }
}
