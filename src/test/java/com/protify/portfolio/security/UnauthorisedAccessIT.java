package com.protify.portfolio.security;

import com.protify.portfolio.api.NoOpDataSourceTestConfig;
import com.protify.portfolio.api.PortfolioApplication;
import com.protify.portfolio.security.support.TestJwtSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * D1-B1 — full-stack proof, real {@code PortfolioApplication} context, that an unauthorised
 * request never leaks a stack trace, SQL or an internal class name. None of the three cases
 * here ever reach a repository, so — like {@code ApplicationContextLoadsTest} — DataSource and
 * Flyway autoconfiguration are excluded and {@link NoOpDataSourceTestConfig} stands in; this
 * needs no Docker and no local MySQL to run.
 */
@SpringBootTest(
        classes = PortfolioApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.autoconfigure.exclude="
                        + "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration",
                "management.health.db.enabled=false"
        })
@Import({NoOpDataSourceTestConfig.class, UnauthorisedAccessIT.JwtDecoderTestConfig.class})
@AutoConfigureMockMvc
class UnauthorisedAccessIT {

    @Autowired
    private MockMvc mockMvc;

    private static final TestJwtSupport JWT_SUPPORT = new TestJwtSupport();

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
    void shouldReturn401WithNoAuthorizationHeader() throws Exception {
        assertCleanUnauthorised(mockMvc.perform(get("/api/v1/me")));
    }

    @Test
    void shouldReturn401WithGarbageAuthorizationHeader() throws Exception {
        assertCleanUnauthorised(mockMvc.perform(get("/api/v1/me")
                .header("Authorization", "Bearer this-is-not-a-jwt")));
    }

    @Test
    void shouldReturn401ForValidShapedTokenSignedByTheWrongKey() throws Exception {
        String token = JWT_SUPPORT.tokenSignedWithWrongKey("user-1");

        assertCleanUnauthorised(mockMvc.perform(get("/api/v1/me")
                .header("Authorization", "Bearer " + token)));
    }

    private static void assertCleanUnauthorised(ResultActions result) throws Exception {
        String body = result
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("Exception");
        assertThat(body).doesNotContain("\tat ");
        assertThat(body).doesNotContainIgnoringCase("com.protify");
        assertThat(body).doesNotContainIgnoringCase("select ");
        assertThat(body).doesNotContainIgnoringCase(" sql");
    }
}
