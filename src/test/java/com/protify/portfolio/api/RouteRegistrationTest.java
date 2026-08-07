package com.protify.portfolio.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Every endpoint is actually routed <b>in the real application context</b>, at the path the
 * contract says.
 *
 * <p>A {@code @WebMvcTest} slice cannot answer this question. It instantiates the one controller
 * it names, so a route resolves there whether or not the class is component-scanned, whether or
 * not {@link com.protify.portfolio.api.config.WebConfig}'s {@code /api/v1} prefix applies to its
 * package, and whether or not two controllers collide on a pattern. All three of those failures
 * look identical from the browser — a 404 — and identical to "this endpoint was never built",
 * which is the confusion API_CONTRACT.md §18 introduced the 501 rule to avoid in the first place.
 *
 * <p>DataSource and Flyway are excluded for the same reason as
 * {@link ApplicationContextLoadsTest}: this asks about routing, not DB connectivity, and it
 * should cost no Docker.
 */
@SpringBootTest(
        properties = {
                "spring.autoconfigure.exclude="
                        + "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration",
                "management.health.db.enabled=false"
        })
@Import(NoOpDataSourceTestConfig.class)
class RouteRegistrationTest {

    /** Qualified by name: Actuator contributes a second {@code RequestMappingHandlerMapping}
     * ({@code controllerEndpointHandlerMapping}) for its own endpoints, and it is the MVC one
     * that owns the application's routes. */
    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/v1/me",
            "/api/v1/me/preferences",
            "/api/v1/overview",
            "/api/v1/portfolios",
            "/api/v1/portfolios/{id}",
            "/api/v1/portfolios/{portfolioId}/transactions",
            "/api/v1/portfolios/{portfolioId}/transactions/import",
            "/api/v1/portfolios/{portfolioId}/holdings",
            "/api/v1/portfolios/{portfolioId}/valuation",
            "/api/v1/portfolios/{portfolioId}/performance",
            "/api/v1/portfolios/{portfolioId}/allocation",
            "/api/v1/portfolios/{portfolioId}/insights",
            "/api/v1/i18n/translate",
            "/api/v1/instruments"
    })
    void endpointIsRoutedAtTheContractedPath(String path) {
        assertThat(registeredPatterns()).contains(path);
    }

    /**
     * The {@code /api/v1} prefix is applied by base package, not by a blanket
     * {@code @RestController} predicate, so springdoc's and Actuator's own controllers must stay
     * at their root paths. A controller added to the wrong package would silently move them.
     */
    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/actuator/health", "/api/v1/v3/api-docs"})
    void infrastructureEndpointsAreNotPrefixed(String wronglyPrefixed) {
        assertThat(registeredPatterns()).doesNotContain(wronglyPrefixed);
    }

    private Set<String> registeredPatterns() {
        return handlerMapping.getHandlerMethods().keySet().stream()
                .flatMap(info -> info.getPathPatternsCondition() == null
                        ? info.getPatternsCondition().getPatterns().stream()
                        : info.getPathPatternsCondition().getPatternValues().stream())
                .collect(Collectors.toSet());
    }
}
