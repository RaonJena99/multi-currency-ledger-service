package com.github.raonjena99.multi_currency_ledger_service.common.security;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

/**
 * {@link PrincipalResolver} 가 해석한 주체를 SecurityContext 에 채우는 필터입니다.
 */
@Component
@RequiredArgsConstructor
public class PrincipalAuthenticationFilter extends OncePerRequestFilter {

    private final PrincipalResolver principalResolver;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        if (SecurityContextHolder.getContext().getAuthentication() == null) {
            LedgerPrincipal principal = principalResolver.resolve(request);

            if (principal != null) {
                List<SimpleGrantedAuthority> authorities = new ArrayList<>();
                authorities.add(new SimpleGrantedAuthority(LedgerPrincipal.ROLE_CUSTOMER));
                if (principal.admin()) {
                    authorities.add(new SimpleGrantedAuthority(LedgerPrincipal.ROLE_ADMIN));
                }

                var authentication = new UsernamePasswordAuthenticationToken(principal, null, authorities);
                SecurityContextHolder.getContext().setAuthentication(authentication);
            }
        }

        chain.doFilter(request, response);
    }
}
