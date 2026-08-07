package com.protify.portfolio.api.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeIn;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import org.springframework.context.annotation.Configuration;

/**
 * springdoc auto-generates the schema from the controllers already registered under
 * {@code com.protify.portfolio}; this class only adds the one thing it cannot infer — that
 * every endpoint (API_CONTRACT.md §0.1) expects {@code Authorization: Bearer <google-id-token>}.
 * Swagger UI ({@code /swagger-ui/**}) and {@code /v3/api-docs} stay reachable <em>without</em>
 * authentication themselves once Dev B's {@code SecurityConfig} permits those paths — only the
 * documented endpoints require the token.
 */
@OpenAPIDefinition(
        info = @Info(title = "Protify Portfolio Manager API", version = "v1"),
        security = @SecurityRequirement(name = OpenApiConfig.BEARER_JWT_SCHEME))
@SecurityScheme(
        name = OpenApiConfig.BEARER_JWT_SCHEME,
        type = SecuritySchemeType.HTTP,
        scheme = "bearer",
        bearerFormat = "JWT",
        in = SecuritySchemeIn.HEADER)
@Configuration
public class OpenApiConfig {

    static final String BEARER_JWT_SCHEME = "bearer-jwt";
}
