package com.protify.portfolio.graphql;

import graphql.analysis.MaxQueryComplexityInstrumentation;
import graphql.analysis.MaxQueryDepthInstrumentation;
import graphql.execution.instrumentation.ChainedInstrumentation;
import graphql.execution.instrumentation.Instrumentation;
import graphql.language.StringValue;
import graphql.schema.Coercing;
import graphql.schema.CoercingParseLiteralException;
import graphql.schema.CoercingParseValueException;
import graphql.schema.CoercingSerializeException;
import graphql.schema.GraphQLScalarType;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.springframework.boot.autoconfigure.graphql.GraphQlSourceBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.graphql.execution.RuntimeWiringConfigurer;

/**
 * day-5-dev-C.md D5-C1: "query depth limit 6, complexity limit 200 — without them, a nested
 * query is a denial-of-service." Wired directly on {@link GraphQlSourceBuilderCustomizer}
 * rather than left to component-scanned {@code Instrumentation} beans, so it does not depend on
 * exactly which {@code Instrumentation} beans Boot's autoconfiguration happens to collect.
 *
 * <p>{@code Date}/{@code DateTime} are registered here rather than relying on GraphQL Java's
 * built-in {@code String} scalar to coerce a {@link LocalDate}/{@link Instant} implicitly —
 * the Spring GraphQL docs are explicit that Jackson-style serialisation isn't in play server
 * side, and a custom scalar is the documented way to carry a non-String Java type over the wire.
 */
@Configuration(proxyBeanMethods = false)
public class GraphQlConfig {

    private static final int MAX_QUERY_DEPTH = 6;
    private static final int MAX_QUERY_COMPLEXITY = 200;

    @Bean
    public GraphQlSourceBuilderCustomizer queryLimitsCustomizer() {
        Instrumentation limits = new ChainedInstrumentation(List.of(
                new MaxQueryDepthInstrumentation(MAX_QUERY_DEPTH),
                new MaxQueryComplexityInstrumentation(MAX_QUERY_COMPLEXITY)));
        return builder -> builder.configureGraphQl(gqlBuilder -> gqlBuilder.instrumentation(limits));
    }

    @Bean
    public RuntimeWiringConfigurer scalarsConfigurer() {
        return wiring -> wiring.scalar(dateScalar()).scalar(dateTimeScalar());
    }

    private static GraphQLScalarType dateScalar() {
        return GraphQLScalarType.newScalar()
                .name("Date")
                .description("ISO-8601 calendar date")
                .coercing(new Coercing<LocalDate, String>() {
                    @Override
                    public String serialize(Object input) throws CoercingSerializeException {
                        if (input instanceof LocalDate date) {
                            return date.toString();
                        }
                        throw new CoercingSerializeException("Expected a LocalDate, got " + input);
                    }

                    @Override
                    public LocalDate parseValue(Object input) throws CoercingParseValueException {
                        try {
                            return LocalDate.parse(String.valueOf(input));
                        } catch (Exception e) {
                            throw new CoercingParseValueException("Not a valid Date: " + input, e);
                        }
                    }

                    @Override
                    public LocalDate parseLiteral(Object input) throws CoercingParseLiteralException {
                        if (input instanceof StringValue stringValue) {
                            try {
                                return LocalDate.parse(stringValue.getValue());
                            } catch (Exception e) {
                                throw new CoercingParseLiteralException(
                                        "Not a valid Date: " + stringValue.getValue(), e);
                            }
                        }
                        throw new CoercingParseLiteralException("Expected a StringValue for Date");
                    }
                })
                .build();
    }

    private static GraphQLScalarType dateTimeScalar() {
        return GraphQLScalarType.newScalar()
                .name("DateTime")
                .description("ISO-8601 instant")
                .coercing(new Coercing<Instant, String>() {
                    @Override
                    public String serialize(Object input) throws CoercingSerializeException {
                        if (input instanceof Instant instant) {
                            return instant.toString();
                        }
                        throw new CoercingSerializeException("Expected an Instant, got " + input);
                    }

                    @Override
                    public Instant parseValue(Object input) throws CoercingParseValueException {
                        try {
                            return Instant.parse(String.valueOf(input));
                        } catch (Exception e) {
                            throw new CoercingParseValueException("Not a valid DateTime: " + input, e);
                        }
                    }

                    @Override
                    public Instant parseLiteral(Object input) throws CoercingParseLiteralException {
                        if (input instanceof StringValue stringValue) {
                            try {
                                return Instant.parse(stringValue.getValue());
                            } catch (Exception e) {
                                throw new CoercingParseLiteralException(
                                        "Not a valid DateTime: " + stringValue.getValue(), e);
                            }
                        }
                        throw new CoercingParseLiteralException("Expected a StringValue for DateTime");
                    }
                })
                .build();
    }
}
