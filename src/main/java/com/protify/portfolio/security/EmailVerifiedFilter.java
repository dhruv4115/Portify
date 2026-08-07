package com.protify.portfolio.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * D1-B1 / D1-B2 — {@code email_verified: false} is rejected with 403, never 401: the token
 * itself is perfectly valid, the account behind it just isn't. An unverified email claim is
 * attacker-controlled, so this is checked on every authenticated request, before any controller
 * (and before {@code CurrentUserResolver}'s JIT provisioning) ever sees the token.
 *
 * <p>Registered after {@code AuthorizationFilter} (see {@link SecurityConfig}) so it only runs
 * once a request has already passed the "must be authenticated" check — an unauthenticated
 * request never reaches this filter and correctly gets 401, not 403.
 */
public class EmailVerifiedFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                     FilterChain filterChain) throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (authentication instanceof JwtAuthenticationToken jwtAuth) {
            Jwt jwt = jwtAuth.getToken();
            Boolean emailVerified = jwt.getClaimAsBoolean("email_verified");
            if (!Boolean.TRUE.equals(emailVerified)) {
                throw new AccessDeniedException("Email address is not verified");
            }
        }

        filterChain.doFilter(request, response);
    }
}
