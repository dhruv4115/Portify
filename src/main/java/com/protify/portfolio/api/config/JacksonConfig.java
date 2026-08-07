package com.protify.portfolio.api.config;

import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.core.JsonGenerator;
import java.io.IOException;
import java.math.BigDecimal;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Money is {@code BigDecimal} in Java and a JSON <em>string</em> on the wire — never a bare
 * JSON number. JavaScript parses a bare JSON number as an IEEE-754 double, which cannot
 * represent decimal money exactly, so a number here would silently corrupt the value the
 * moment a browser renders it (API_CONTRACT.md §0.2). Configured once, here, so every DTO with
 * a {@code BigDecimal} field inherits it for free.
 *
 * <p>{@code toPlainString()}, not {@code toString()}: {@code BigDecimal#toString()} can fall
 * back to scientific notation for very small/large values, and a JSON string like
 * {@code "1E+3"} is not a valid amount.
 */
@Configuration
public class JacksonConfig {

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer moneyAndDateJacksonCustomizer() {
        return builder -> builder
                .featuresToDisable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .serializerByType(BigDecimal.class, new BigDecimalAsStringSerializer());
    }

    private static final class BigDecimalAsStringSerializer extends JsonSerializer<BigDecimal> {
        @Override
        public void serialize(BigDecimal value, JsonGenerator gen, SerializerProvider serializers)
                throws IOException {
            gen.writeString(value.toPlainString());
        }
    }
}
