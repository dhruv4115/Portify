package com.protify.portfolio.api.dto;

import com.protify.portfolio.common.enums.CurrencyCode;
import java.time.Instant;

/**
 * API_CONTRACT.md §2-§4. One shape for the list, create-response and detail bodies —
 * frozen today (D2-C4) to always carry {@code costBasis}/{@code cashBalance}/{@code
 * realisedPnl}/{@code totalValue} rather than the leaner list/create shape originally
 * sketched, for parity with the GraphQL {@code Portfolio} type (§19), which has never
 * distinguished a "list" shape from a "detail" one. See the Day 2 changelog entry.
 *
 * <p>{@code avgCost}/{@code lastPrice}-style native-currency fields never appear here — every
 * field on this DTO is a portfolio-level total, always in {@code baseCurrency} (§0.3).
 */
public record PortfolioResponse(
        Long id,
        String name,
        CurrencyCode baseCurrency,
        int holdingCount,
        MoneyDto marketValue,
        MoneyDto costBasis,
        MoneyDto cashBalance,
        MoneyDto totalValue,
        MoneyDto unrealisedPnl,
        MoneyDto realisedPnl,
        String unrealisedPnlPct,
        DataQualityDto dataQuality,
        Instant createdAt,
        Instant updatedAt) {
}
