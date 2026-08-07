package com.protify.portfolio.common.port;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Implemented by adapters in {@code portfolio-platform} (ADR-0008). Lives here so
 * {@code portfolio-core} can call it while being unable to import {@code portfolio-platform} —
 * the valuation engine physically cannot reach an HTTP client. Frozen with the rest of
 * {@code portfolio-common} at the end of Day 1.
 */
public interface MarketDataProvider {

    Optional<PriceQuote> latestPrice(String symbol);

    List<PriceQuote> dailyCloses(String symbol, LocalDate from, LocalDate to);

    String sourceName();
}
