package com.github.raonjena99.multi_currency_ledger_service.common.security;

import org.springframework.boot.actuate.autoconfigure.endpoint.web.WebEndpointProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import lombok.RequiredArgsConstructor;

/**
 * API 접근 통제를 정의합니다.
 *
 * <p>거래 및 포트폴리오 API 는 인증을 요구하고, 백오피스 대사 API 는 관리자 권한을 요구합니다.
 * 계좌 <b>소유권</b> 검증은 URL 규칙으로 표현할 수 없으므로 {@link AccountOwnershipGuard} 가
 * 컨트롤러 진입 시점에 수행합니다.
 */
@Configuration
@RequiredArgsConstructor
public class SecurityConfig {

    private final PrincipalAuthenticationFilter principalAuthenticationFilter;
    private final WebEndpointProperties webEndpointProperties;

    @Bean
    SecurityFilterChain apiSecurityFilterChain(HttpSecurity http) throws Exception {
        String actuatorBase = webEndpointProperties.getBasePath();

        http
            // 상태 없는 토큰 기반 API 이므로 세션과 CSRF 토큰을 사용하지 않는다.
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .httpBasic(basic -> basic.disable())
            .formLogin(form -> form.disable())
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(actuatorBase + "/health", actuatorBase + "/health/**", actuatorBase + "/info").permitAll()
                .requestMatchers(actuatorBase + "/**").hasAuthority(LedgerPrincipal.ROLE_ADMIN)
                .requestMatchers("/api/v1/admin/**").hasAuthority(LedgerPrincipal.ROLE_ADMIN)
                .requestMatchers("/api/v1/accounts/**", "/api/v1/portfolios/**").authenticated()
                .anyRequest().denyAll())
            .addFilterBefore(principalAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
            .exceptionHandling(handling -> handling
                .authenticationEntryPoint((request, response, ex) ->
                        writeError(response, HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Authentication is required."))
                .accessDeniedHandler((request, response, ex) ->
                        writeError(response, HttpStatus.FORBIDDEN, "FORBIDDEN", "You are not allowed to access this resource.")));

        return http.build();
    }

    private void writeError(jakarta.servlet.http.HttpServletResponse response, HttpStatus status,
                            String code, String message) throws java.io.IOException {
        response.setStatus(status.value());
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":\"" + code + "\",\"message\":\"" + message + "\"}");
    }
}
