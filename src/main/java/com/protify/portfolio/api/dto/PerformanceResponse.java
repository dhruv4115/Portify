package com.protify.portfolio.api.dto;

import com.protify.portfolio.common.enums.CurrencyCode;
import java.time.LocalDate;
import java.util.List;

/**
 * API_CONTRACT.md §12 — the customer's "view performance", and the shape the Recharts
 * {@code LineChart} binds to.
 *
 * <p>{@code from}/{@code to} echo what was requested, not what was returned: points before the
 * portfolio's first transaction are omitted rather than zero-filled, so an empty
 * {@code points} array against a wide window is a correct answer, not a missing one.
 */
public record PerformanceResponse(
        long portfolioId,
        CurrencyCode currency,
        LocalDate from,
        LocalDate to,
        PerformanceInterval interval,
        List<PerformancePointDto> points,
        PerformanceSummaryDto summary,
        DataQualityDto dataQuality) {

    public PerformanceResponse {
        points = points == null ? List.of() : List.copyOf(points);
    }
}
