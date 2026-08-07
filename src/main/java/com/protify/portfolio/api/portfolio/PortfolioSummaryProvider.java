package com.protify.portfolio.api.portfolio;

import com.protify.portfolio.portfolio.Portfolio;

/**
 * The seam for Dev A's real valuation engine (PLAN.md §3 {@code valuation/}, not built as of
 * Day 2 — only {@code ProjectionEngine} and the repositories landed today). {@link
 * PortfolioController} depends on this interface, not on a concrete valuation
 * implementation, so it compiles and is unit-testable today; swap
 * {@link PlaceholderPortfolioSummaryProvider} for the real thing once it exists.
 */
public interface PortfolioSummaryProvider {

    PortfolioSummary summarize(Portfolio portfolio);
}
