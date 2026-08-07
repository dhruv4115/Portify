package com.protify.portfolio.api.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.protify.portfolio.api.config.JacksonConfig;
import com.protify.portfolio.common.enums.CurrencyCode;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

/**
 * D2-C1: exercises the real {@link JacksonConfig} customizer directly (no Spring context
 * needed) so the test proves the actual production configuration, not a re-implementation of
 * it.
 */
class MoneySerializationTest {

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        Jackson2ObjectMapperBuilder builder = new Jackson2ObjectMapperBuilder();
        new JacksonConfig().moneyAndDateJacksonCustomizer().customize(builder);
        objectMapper = builder.build();
    }

    @Test
    void moneyDtoSerialisesAmountAsAJsonString() throws Exception {
        MoneyDto money = MoneyDto.of(new BigDecimal("18654.75"), CurrencyCode.INR);

        String json = objectMapper.writeValueAsString(money);

        assertThat(json).contains("\"amount\":\"18654.7500\"");
        assertThat(json).contains("\"currency\":\"INR\"");
    }

    @Test
    void aBareBigDecimalFieldNeverSerialisesAsAJsonNumber() throws Exception {
        record Wrapper(BigDecimal value) {
        }

        String json = objectMapper.writeValueAsString(new Wrapper(new BigDecimal("100.50")));

        // The whole point of the global customizer: even a BigDecimal that never went through
        // MoneyDto still comes out quoted, not as a bare JSON number.
        assertThat(json).isEqualTo("{\"value\":\"100.50\"}");
    }

    @Test
    void nullUnrealisedPnlPctSerialisesAsLiteralNullNotOmittedNotTheStringNull() throws Exception {
        PortfolioResponse response = new PortfolioResponse(
                7L, "Growth", CurrencyCode.INR, 0,
                MoneyDto.zero(CurrencyCode.INR), MoneyDto.zero(CurrencyCode.INR),
                MoneyDto.zero(CurrencyCode.INR), MoneyDto.zero(CurrencyCode.INR),
                MoneyDto.zero(CurrencyCode.INR), MoneyDto.zero(CurrencyCode.INR),
                null,
                DataQualityDto.empty(), Instant.parse("2026-07-31T09:00:00Z"), Instant.parse("2026-07-31T09:00:00Z"));

        String json = objectMapper.writeValueAsString(response);

        assertThat(json).contains("\"unrealisedPnlPct\":null");
        assertThat(json).doesNotContain("\"unrealisedPnlPct\":\"null\"");
    }

    @Test
    void datesSerialiseAsIsoStringsNotEpochTimestamps() throws Exception {
        record InstantHolder(Instant when) {
        }

        String json = objectMapper.writeValueAsString(new InstantHolder(Instant.parse("2026-07-31T09:00:00Z")));

        assertThat(json).contains("\"when\":\"2026-07-31T09:00:00Z\"");
    }
}
