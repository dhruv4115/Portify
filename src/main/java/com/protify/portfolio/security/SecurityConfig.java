package com.protify.portfolio.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.servlet.HandlerExceptionResolver;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * D1-B1 — OAuth2 resource server. The Google ID token is the bearer token (ADR-0004); Spring
 * fetches Google's JWKS and validates signature, issuer and audience from
 * {@code spring.security.oauth2.resourceserver.jwt.*} in application.properties. No JWT parsing
 * or validation is hand-written here — every historical JWT vulnerability comes from that.
 *
 * <p>401/403 bodies are <em>not</em> built here: the entry point and access-denied handler both
 * delegate to Spring MVC's {@code HandlerExceptionResolver}, which routes the exception into
 * Dev C's {@code GlobalExceptionHandler} — the same {@code AuthenticationException}/
 * {@code AccessDeniedException} handlers already there, one {@code ProblemDetail} shape
 * everywhere, no second JSON-writing code path to keep in sync.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    private static final String[] PERMIT_ALL = {
            "/actuator/health",
            "/v3/api-docs/**",
            "/swagger-ui/**",
            "/swagger-ui.html"
    };

    @Value("${app.cors.allowed-origins:http://localhost:5173,http://localhost:3000}")
    private List<String> allowedOrigins;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                     EmailVerifiedFilter emailVerifiedFilter,
                                                     AuthenticationEntryPoint authenticationEntryPoint,
                                                     AccessDeniedHandler accessDeniedHandler) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(PERMIT_ALL).permitAll()
                        .anyRequest().authenticated())
                // The entry point MUST also be set here, not only via .exceptionHandling(...):
                // oauth2ResourceServer() installs its own default BearerTokenAuthenticationEntryPoint
                // for bearer-token requests, which otherwise wins and bypasses GlobalExceptionHandler.
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(Customizer.withDefaults())
                        .authenticationEntryPoint(authenticationEntryPoint))
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                // after AuthorizationFilter: only requests that already passed "must be
                // authenticated" reach it, so an unverified email correctly gets 403, never 401.
                .addFilterAfter(emailVerifiedFilter, AuthorizationFilter.class);

        return http.build();
    }

    @Bean
    public AuthenticationEntryPoint authenticationEntryPoint(
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver resolver) {
        return (request, response, authException) -> {
            // R6: the reason must be distinguishable in the logs even though the response body
            // (built by GlobalExceptionHandler, below) is deliberately generic for every case.
            log.warn("Unauthenticated request to {} rejected: {}",
                    request.getRequestURI(), AuthenticationFailureReasons.reasonFor(authException));
            resolver.resolveException(request, response, null, authException);
        };
    }

    @Bean
    public AccessDeniedHandler accessDeniedHandler(
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver resolver) {
        return (request, response, accessDeniedException) ->
                resolver.resolveException(request, response, null, accessDeniedException);
    }

    @Bean
    public EmailVerifiedFilter emailVerifiedFilter() {
        return new EmailVerifiedFilter();
    }

    /**
     * Explicit, not Boot's auto-configured one: {@code OAuth2ResourceServerAutoConfiguration}
     * is excluded globally (application.properties) because its own default
     * {@code SecurityFilterChain} otherwise auto-secures every test slice that doesn't
     * {@code @Import} this class. {@code @Lazy} so this never makes the startup-time network
     * call to fetch Google's JWKS/discovery document unless a request actually presents a
     * bearer token — a request to a {@code permitAll} path (e.g. {@code /actuator/health} in
     * {@code ApplicationContextLoadsTest}) never touches it. {@code @ConditionalOnMissingBean}
     * so a test can still override it with a local-key stub (no test calls Google).
     */
    @Bean
    @Lazy
    @ConditionalOnMissingBean(JwtDecoder.class)
    public JwtDecoder defaultGoogleJwtDecoder(
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuerUri,
            @Value("${spring.security.oauth2.resourceserver.jwt.audiences:}") String audiencesProperty) {
        var decoder = (org.springframework.security.oauth2.jwt.NimbusJwtDecoder) JwtDecoders.fromIssuerLocation(issuerUri);

        List<String> audiences = Arrays.stream(audiencesProperty.split(","))
                .map(String::strip)
                .filter(a -> !a.isBlank())
                .toList();

        List<OAuth2TokenValidator<Jwt>> validators = new ArrayList<>();
        validators.add(JwtValidators.createDefaultWithIssuer(issuerUri));
        if (!audiences.isEmpty()) {
            validators.add(new JwtClaimValidator<List<String>>(JwtClaimNames.AUD,
                    aud -> aud != null && aud.stream().anyMatch(audiences::contains)));
        }
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(validators));
        return decoder;
    }

    private CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(allowedOrigins);
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Correlation-Id"));
        configuration.setExposedHeaders(List.of("X-Correlation-Id"));
        configuration.setAllowCredentials(false);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
