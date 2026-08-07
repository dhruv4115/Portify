package com.protify.portfolio.security;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.protify.portfolio.api.PortfolioApplication;
import com.protify.portfolio.security.support.TestJwtSupport;
import com.protify.portfolio.security.support.TestPingController;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * D1-B1 — proves the resource-server rules with a locally-generated RSA key pair and a stub JWK
 * source. No test here calls Google. Four distinct failure reasons (bad signature, wrong
 * {@code aud}, wrong {@code iss}, expired) must each yield 401, and the body is the exact same
 * {@code ProblemDetail} shape {@code GlobalExceptionHandlerTest} already asserts for every other
 * error — proving the entry point/handler really do delegate there, not build their own JSON.
 *
 * <p>{@code @ContextConfiguration(classes = PortfolioApplication.class)} is required here:
 * {@code @WebMvcTest} finds {@code @SpringBootConfiguration} by searching packages upwards from
 * the test, which only reaches as far as {@code com.protify.portfolio} — {@code
 * PortfolioApplication} lives one level further, in the sibling {@code api} package.
 */
@WebMvcTest(controllers = TestPingController.class)
@ContextConfiguration(classes = PortfolioApplication.class)
@Import({SecurityConfig.class, SecurityConfigTest.JwtDecoderTestConfig.class})
class SecurityConfigTest {

    @Autowired
    private MockMvc mockMvc;

    private static final TestJwtSupport JWT_SUPPORT = new TestJwtSupport();

    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void attachLogAppender() {
        logAppender = new ListAppender<>();
        logAppender.start();
        ((Logger) LoggerFactory.getLogger(SecurityConfig.class)).addAppender(logAppender);
    }

    @AfterEach
    void detachLogAppender() {
        ((Logger) LoggerFactory.getLogger(SecurityConfig.class)).detachAppender(logAppender);
    }

    @TestConfiguration
    static class JwtDecoderTestConfig {
        @Bean
        @org.springframework.context.annotation.Primary
        JwtDecoder jwtDecoder() {
            NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(JWT_SUPPORT.publicKey).build();
            OAuth2TokenValidator<Jwt> withIssuer = JwtValidators.createDefaultWithIssuer(TestJwtSupport.ISSUER);
            OAuth2TokenValidator<Jwt> withAudience = new JwtClaimValidator<List<String>>(JwtClaimNames.AUD,
                    aud -> aud != null && aud.contains(TestJwtSupport.CLIENT_ID));
            decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(withIssuer, withAudience));
            return decoder;
        }
    }

    @Test
    void shouldReturn200WithAValidToken() throws Exception {
        String token = JWT_SUPPORT.validToken("user-1", true);

        mockMvc.perform(get("/api/v1/ping").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void shouldReturn401WithNoToken() throws Exception {
        mockMvc.perform(get("/api/v1/ping"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.type").value("https://portfolio.local/errors/unauthenticated"))
                .andExpect(jsonPath("$.correlationId").exists());
    }

    @Test
    void shouldReturn401ForGarbageToken() throws Exception {
        mockMvc.perform(get("/api/v1/ping").header("Authorization", "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value("https://portfolio.local/errors/unauthenticated"));
    }

    @Test
    void shouldReturn401ForBadSignature() throws Exception {
        String token = JWT_SUPPORT.tokenSignedWithWrongKey("user-1");
        mockMvc.perform(get("/api/v1/ping").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldReturn401ForWrongAudience() throws Exception {
        String token = JWT_SUPPORT.tokenWithWrongAudience("user-1");
        mockMvc.perform(get("/api/v1/ping").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldReturn401ForWrongIssuer() throws Exception {
        String token = JWT_SUPPORT.tokenWithWrongIssuer("user-1");
        mockMvc.perform(get("/api/v1/ping").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldReturn401ForExpiredToken() throws Exception {
        String token = JWT_SUPPORT.expiredToken("user-1");
        mockMvc.perform(get("/api/v1/ping").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void badSignatureWrongAudienceWrongIssuerAndExpiredShouldAllHaveDistinguishableReasons() throws Exception {
        mockMvc.perform(get("/api/v1/ping").header("Authorization", "Bearer " + JWT_SUPPORT.tokenSignedWithWrongKey("user-1")))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/ping").header("Authorization", "Bearer " + JWT_SUPPORT.tokenWithWrongAudience("user-1")))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/ping").header("Authorization", "Bearer " + JWT_SUPPORT.tokenWithWrongIssuer("user-1")))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/ping").header("Authorization", "Bearer " + JWT_SUPPORT.expiredToken("user-1")))
                .andExpect(status().isUnauthorized());

        List<String> loggedReasons = logAppender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .filter(message -> message.contains("rejected"))
                .toList();

        assertThat(loggedReasons).hasSize(4);
        // four distinct, non-blank reasons — this is R6's diagnostic requirement. The response
        // body itself stays the same generic shape for all four (asserted elsewhere) — a 401
        // body must not hand an attacker the specific reason it failed.
        assertThat(loggedReasons).doesNotHaveDuplicates();
        loggedReasons.forEach(reason -> assertThat(reason).isNotBlank());
    }

    @Test
    void shouldReturn403WhenEmailNotVerified() throws Exception {
        String token = JWT_SUPPORT.validToken("user-1", false);

        mockMvc.perform(get("/api/v1/ping").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.type").value("https://portfolio.local/errors/forbidden"));
    }

    @Test
    void shouldPermitActuatorHealthWithoutToken() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isNotEqualTo(401));
    }
}
