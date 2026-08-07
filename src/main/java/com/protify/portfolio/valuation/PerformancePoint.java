package com.protify.portfolio.valuation;

import java.math.BigDecimal;
import java.time.LocalDate;

/** One day of the chart (API_CONTRACT.md §12). {@code filled} is true when this day's price (or
 * FX rate) was forward-filled from an earlier date rather than fresh for {@code date} itself —
 * a weekend or market holiday, not a statement about whether a transaction happened that day. */
public record PerformancePoint(
        LocalDate date,
        BigDecimal marketValue,
        BigDecimal costBasis,
        BigDecimal cashBalance,
        BigDecimal totalValue,
        BigDecimal unrealisedPnl,
        boolean filled) {
}
