package com.protify.portfolio.valuation;

import java.math.BigDecimal;

/** {@code netContributions} is deposits minus withdrawals within the reported window, in the
 * target currency at each transfer's own trade date — so {@code percentChange} reflects market
 * movement, not the user's own money moving in and out (API_CONTRACT.md §12). */
public record PerformanceSummary(
        BigDecimal startValue,
        BigDecimal endValue,
        BigDecimal absoluteChange,
        BigDecimal percentChange,
        BigDecimal netContributions) {
}
