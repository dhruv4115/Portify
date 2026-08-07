package com.protify.portfolio.api.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.HandlerTypePredicate;
import org.springframework.web.servlet.config.annotation.PathMatchConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Applies {@code /api/v1} to every controller under {@code com.protify.portfolio} once, here,
 * instead of repeating it in every {@code @RequestMapping}. Scoped by base package (not a
 * blanket {@code @RestController} predicate) so it does not also prefix springdoc's and
 * Actuator's own controllers, which must stay reachable at their default root paths
 * ({@code /v3/api-docs}, {@code /swagger-ui/**}, {@code /actuator/health}).
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private static final String API_BASE_PATH = "/api/v1";

    @Override
    public void configurePathMatch(PathMatchConfigurer configurer) {
        configurer.addPathPrefix(API_BASE_PATH,
                HandlerTypePredicate.forBasePackage("com.protify.portfolio"));
    }
}
