package com.protify.portfolio.graphql;

import com.protify.portfolio.api.error.RequestValidationException;
import com.protify.portfolio.common.error.CurrencyMismatchException;
import com.protify.portfolio.common.error.DomainException;
import com.protify.portfolio.common.error.InsufficientQuantityException;
import com.protify.portfolio.common.error.NotFoundException;
import com.protify.portfolio.common.error.UpstreamException;
import com.protify.portfolio.common.error.ValidationException;
import graphql.GraphQLError;
import graphql.GraphqlErrorBuilder;
import graphql.schema.DataFetchingEnvironment;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.graphql.execution.DataFetcherExceptionResolverAdapter;
import org.springframework.graphql.execution.ErrorType;
import org.springframework.stereotype.Component;

/**
 * day-5-dev-C.md D5-C1: "errors use GraphQL's errors[] array, not ProblemDetail — the spec
 * mandates it. Put correlationId and a classification slug in extensions so failures stay
 * traceable across both APIs." The classification mapping mirrors {@code
 * GlobalExceptionHandler}'s (REST's equivalent single choke point) so the same domain exception
 * always reads the same way regardless of which API surfaced it.
 *
 * <p>Anything not matched here (a bug, not a domain rule) falls through to Spring GraphQL's own
 * default handling — an {@code INTERNAL_ERROR} with a generic message — same non-negotiable as
 * {@code GlobalExceptionHandler#handleUnexpected}: no stack trace, no SQL fragment, no {@code
 * com.protify} class name ever reaches a response body.
 */
@Component
public class GraphQlExceptionResolver extends DataFetcherExceptionResolverAdapter {

    private static final String MDC_CORRELATION_ID_KEY = "correlationId";

    @Override
    protected GraphQLError resolveToSingleError(Throwable ex, DataFetchingEnvironment env) {
        ErrorType type = classify(ex);
        if (type == null) {
            return null;
        }

        Map<String, Object> extensions = new LinkedHashMap<>();
        extensions.put("correlationId", correlationId());
        extensions.put("classification", type.name());

        return GraphqlErrorBuilder.newError(env)
                .errorType(type)
                .message(safeMessage(ex))
                .extensions(extensions)
                .build();
    }

    private static ErrorType classify(Throwable ex) {
        return switch (ex) {
            case NotFoundException e -> ErrorType.NOT_FOUND;
            case ValidationException e -> ErrorType.BAD_REQUEST;
            case InsufficientQuantityException e -> ErrorType.BAD_REQUEST;
            case CurrencyMismatchException e -> ErrorType.BAD_REQUEST;
            case UpstreamException e -> ErrorType.INTERNAL_ERROR;
            case RequestValidationException e -> ErrorType.BAD_REQUEST;
            default -> null;
        };
    }

    private static String safeMessage(Throwable ex) {
        if (ex instanceof DomainException domainException) {
            return domainException.getMessage();
        }
        if (ex instanceof RequestValidationException requestValidationException) {
            return requestValidationException.getMessage();
        }
        return "An unexpected error occurred.";
    }

    private static String correlationId() {
        String mdc = MDC.get(MDC_CORRELATION_ID_KEY);
        return mdc != null ? mdc : UUID.randomUUID().toString();
    }
}
