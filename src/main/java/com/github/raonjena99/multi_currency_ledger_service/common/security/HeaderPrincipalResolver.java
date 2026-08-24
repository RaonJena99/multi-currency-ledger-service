package com.github.raonjena99.multi_currency_ledger_service.common.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
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
 * 보낸 동일 헤더를 반드시 제거(strip)하도록 설정하십시오. 네트워크 격리만으로 이 전제를 보장할 수
 * 없다면 {@code ledger.security.gateway-secret} 을 설정하십시오. 설정 시 게이트웨이가
 * {@value #GATEWAY_SECRET_HEADER} 헤더에 같은 값을 실어 보내야만 인증 헤더를 신뢰합니다.
 */
@Slf4j
@Component
@ConditionalOnMissingBean(ignored = HeaderPrincipalResolver.class, value = PrincipalResolver.class)
public class HeaderPrincipalResolver implements PrincipalResolver {

    public static final String SUBJECT_HEADER = "X-Auth-Subject";
    public static final String ACCOUNT_HEADER = "X-Auth-Account-Id";
    public static final String ROLES_HEADER = "X-Auth-Roles";
    public static final String GATEWAY_SECRET_HEADER = "X-Gateway-Secret";

    private final String gatewaySecret;

    public HeaderPrincipalResolver(
            @Value("${ledger.security.gateway-secret:}") String gatewaySecret) {
        this.gatewaySecret = gatewaySecret;
        if (!StringUtils.hasText(gatewaySecret)) {
            log.warn("ledger.security.gateway-secret 이 설정되지 않았습니다. 인증 헤더를 무조건 신뢰하므로 "
                    + "게이트웨이가 클라이언트의 X-Auth-* 헤더를 반드시 제거하는 환경에서만 사용하십시오.");
        }
    }

    @Override
    public LedgerPrincipal resolve(HttpServletRequest request) {
        if (!verifyGatewaySecret(request)) {
            return null;
        }

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
        boolean admin = hasAdminRole(roles);

        return new LedgerPrincipal(subject, accountId, admin);
    }

    /**
     * 역할 목록에 관리자 역할이 <b>정확히</b> 포함되어 있는지 검사합니다.
     *
     * <p>부분 문자열 검사({@code contains("ADMIN")})를 쓰면 {@code NOT_ADMIN},
     * {@code ADMIN_READONLY} 같은 역할까지 관리자로 승격됩니다. 반드시 토큰 단위로 비교합니다.
     */
    private boolean hasAdminRole(String roles) {
        if (!StringUtils.hasText(roles)) {
            return false;
        }
        for (String role : roles.split(",")) {
            String normalized = role.trim().toUpperCase();
            if (normalized.equals("ADMIN") || normalized.equals("ROLE_ADMIN")) {
                return true;
            }
        }
        return false;
    }

    /**
     * 게이트웨이 공유 시크릿이 설정된 경우, 요청이 게이트웨이를 거쳐 왔는지 검증합니다.
     *
     * <p>타이밍 공격으로 시크릿이 유출되지 않도록 상수 시간 비교를 사용합니다.
     */
    private boolean verifyGatewaySecret(HttpServletRequest request) {
        if (!StringUtils.hasText(gatewaySecret)) {
            return true;
        }
        String provided = request.getHeader(GATEWAY_SECRET_HEADER);
        if (provided == null) {
            return false;
        }
        boolean matches = MessageDigest.isEqual(
                gatewaySecret.getBytes(StandardCharsets.UTF_8),
                provided.getBytes(StandardCharsets.UTF_8));
        if (!matches) {
            log.warn("게이트웨이 시크릿이 일치하지 않는 요청을 거부합니다. remoteAddr={}", request.getRemoteAddr());
        }
        return matches;
    }
}
