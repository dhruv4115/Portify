package com.protify.portfolio.security;

import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;

/**
 * D1-B1 / {@code /docs/RISKS.md} R6 — a bad signature, a wrong {@code aud}, a wrong {@code iss}
 * and an expired token must be distinguishable reasons <em>in the logs</em>, even though the
 * response body Dev C's {@code GlobalExceptionHandler} sends back is deliberately the same
 * generic {@code /errors/unauthenticated} shape for all of them (a 401 body must not tell an
 * attacker which check it failed). {@link SecurityConfig}'s entry point logs the result of this
 * before delegating to {@code GlobalExceptionHandler}.
 */
final class AuthenticationFailureReasons {

    private AuthenticationFailureReasons() {
    }

    static String reasonFor(AuthenticationException authException) {
        if (authException instanceof OAuth2AuthenticationException oauth2Ex && oauth2Ex.getError() != null) {
            String description = oauth2Ex.getError().getDescription();
            return description != null ? description : oauth2Ex.getError().getErrorCode();
        }
        return authException.getMessage() != null
                ? authException.getMessage()
                : "A valid bearer token is required";
    }
}
