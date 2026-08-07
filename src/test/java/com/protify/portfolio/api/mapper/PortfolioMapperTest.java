package com.protify.portfolio.api.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.protify.portfolio.api.dto.DataQualityDto;
import com.protify.portfolio.api.dto.PortfolioResponse;
import com.protify.portfolio.api.portfolio.PortfolioSummary;
import com.protify.portfolio.common.enums.CurrencyCode;
import com.protify.portfolio.portfolio.Portfolio;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class PortfolioMapperTest {

    private final PortfolioMapper mapper = new PortfolioMapper();

    @Test
    void mapsEveryFieldInTheBaseCurrency() {
        Instant createdAt = Instant.parse("2026-02-14T11:03:00Z");
        Instant updatedAt = Instant.parse("2026-07-28T10:15:00Z");
        Portfolio portfolio = new Portfolio(7L, 42L, "Growth", CurrencyCode.INR, createdAt, updatedAt);
        PortfolioSummary summary = new PortfolioSummary(
                3,
                new BigDecimal("412873.54"),
                new BigDecimal("374662.63"),
                new BigDecimal("25000.00"),
                new BigDecimal("38210.91"),
                new BigDecimal("4120.00"),
                "10.2100",
                new DataQualityDto(LocalDate.parse("2026-07-29"), LocalDate.parse("2026-07-29"), false));

        PortfolioResponse response = mapper.toResponse(portfolio, summary);

        assertThat(response.id()).isEqualTo(7L);
        assertThat(response.name()).isEqualTo("Growth");
        assertThat(response.baseCurrency()).isEqualTo(CurrencyCode.INR);
        assertThat(response.holdingCount()).isEqualTo(3);
        assertThat(response.marketValue().amount()).isEqualTo("412873.5400");
        assertThat(response.marketValue().currency()).isEqualTo(CurrencyCode.INR);
        assertThat(response.costBasis().amount()).isEqualTo("374662.6300");
        assertThat(response.cashBalance().amount()).isEqualTo("25000.0000");
        assertThat(response.totalValue().amount()).isEqualTo("437873.5400");
        assertThat(response.unrealisedPnl().amount()).isEqualTo("38210.9100");
        assertThat(response.realisedPnl().amount()).isEqualTo("4120.0000");
        assertThat(response.unrealisedPnlPct()).isEqualTo("10.2100");
        assertThat(response.dataQuality().stale()).isFalse();
        assertThat(response.createdAt()).isEqualTo(createdAt);
        assertThat(response.updatedAt()).isEqualTo(updatedAt);
    }

    @Test
    void emptyPortfolioMapsToNullPctNotZeroOrException() {
        Portfolio portfolio = new Portfolio(9L, 1L, "New", CurrencyCode.USD, Instant.EPOCH, Instant.EPOCH);
        PortfolioSummary summary = new PortfolioSummary(
                0, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                null, DataQualityDto.empty());

        PortfolioResponse response = mapper.toResponse(portfolio, summary);

        assertThat(response.unrealisedPnlPct()).isNull();
        assertThat(response.holdingCount()).isZero();
        assertThat(response.marketValue().amount()).isEqualTo("0.0000");
    }
}
