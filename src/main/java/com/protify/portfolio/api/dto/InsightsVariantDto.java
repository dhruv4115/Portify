package com.protify.portfolio.api.dto;

import java.util.List;

/**
 * One reading of the same portfolio, by one engine (API_CONTRACT.md §18).
 *
 * <p>Both variants in a response are generated from a single {@code PortfolioSummary} snapshot,
 * which is the whole reason they are returned together rather than fetched separately. Two
 * requests could straddle a price refresh, and a toggle whose two sides quietly describe
 * different portfolios is worse than no toggle at all — the reader would attribute the
 * difference to the engine, which is exactly the comparison it exists to support.
 */
public record InsightsVariantDto(
        String engine,
        String summary,
        List<InsightsHighlightDto> highlights) {

    public InsightsVariantDto {
        highlights = highlights == null ? List.of() : List.copyOf(highlights);
    }
}
