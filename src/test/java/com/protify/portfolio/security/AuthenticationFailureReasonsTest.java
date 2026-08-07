package com.protify.portfolio.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;

import static org.assertj.core.api.Assertions.assertThat;

class AuthenticationFailureReasonsTest {

    @Test
    void usesTheOAuth2ErrorDescriptionWhenPresent() {
        OAuth2AuthenticationException ex = new OAuth2AuthenticationException(
                new OAuth2Error("invalid_token", "Jwt expired at 2026-01-01T00:00:00Z", null));

        assertThat(AuthenticationFailureReasons.reasonFor(ex)).isEqualTo("Jwt expired at 2026-01-01T00:00:00Z");
    }

    @Test
    void fallsBackToTheErrorCodeWhenNoDescription() {
        OAuth2AuthenticationException ex = new OAuth2AuthenticationException(
                new OAuth2Error("invalid_token", null, null));

        assertThat(AuthenticationFailureReasons.reasonFor(ex)).isEqualTo("invalid_token");
    }

    @Test
    void fallsBackToTheExceptionMessageForNonOAuth2Exceptions() {
        assertThat(AuthenticationFailureReasons.reasonFor(new BadCredentialsException("bad creds")))
                .isEqualTo("bad creds");
    }

    @Test
    void neverReturnsBlank() {
        assertThat(AuthenticationFailureReasons.reasonFor(new BadCredentialsException(null))).isNotBlank();
    }
}
