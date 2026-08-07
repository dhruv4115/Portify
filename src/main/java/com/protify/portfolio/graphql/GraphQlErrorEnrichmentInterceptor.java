package com.protify.portfolio.graphql;

import graphql.GraphQLError;
import graphql.GraphqlErrorBuilder;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.graphql.ResponseError;
import org.springframework.graphql.server.WebGraphQlInterceptor;
import org.springframework.graphql.server.WebGraphQlRequest;
import org.springframework.graphql.server.WebGraphQlResponse;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * day-5-dev-C.md D5-C1's "correlationId and a classification slug in extensions" requirement,
 * for the one class of failure {@link GraphQlExceptionResolver} cannot reach: errors raised
 * during parsing/validation — an over-{@code depth}/{@code complexity} query, most notably —
 * which never invoke a data fetcher at all (Spring GraphQL's "Request Exceptions" doc section).
 * A {@link WebGraphQlInterceptor} is the documented seam for exactly this case.
 *
 * <p>Runs for every response, not only invalid ones, so a {@link GraphQlExceptionResolver} error
 * that already carries {@code correlationId}/{@code classification} is left untouched
 * ({@code putIfAbsent}) rather than double-stamped.
 */
@Component
public class GraphQlErrorEnrichmentInterceptor implements WebGraphQlInterceptor {

    private static final String MDC_CORRELATION_ID_KEY = "correlationId";

    @Override
    public Mono<WebGraphQlResponse> intercept(WebGraphQlRequest request, Chain chain) {
        return chain.next(request).map(response -> {
            if (response.getErrors().isEmpty()) {
                return response;
            }
            String correlationId = correlationId();
            List<GraphQLError> enriched = response.getErrors().stream()
                    .map(error -> enrich(error, correlationId))
                    .toList();
            return response.transform(builder -> builder.errors(enriched).build());
        });
    }

    private static GraphQLError enrich(ResponseError error, String correlationId) {
        Map<String, Object> extensions = new LinkedHashMap<>();
        if (error.getExtensions() != null) {
            extensions.putAll(error.getExtensions());
        }
        extensions.putIfAbsent("classification",
                error.getErrorType() != null ? error.getErrorType().toString() : "UNKNOWN");
        extensions.putIfAbsent("correlationId", correlationId);

        return GraphqlErrorBuilder.newError()
                .message(error.getMessage())
                .extensions(extensions)
                .build();
    }

    private static String correlationId() {
        String mdc = MDC.get(MDC_CORRELATION_ID_KEY);
        return mdc != null ? mdc : UUID.randomUUID().toString();
    }
}
