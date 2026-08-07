package com.protify.portfolio.valuation.spi;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;

/**
 * How the valuation layer asks for a price, without knowing that {@code marketdata} exists.
 *
 * <p><b>Why this lives here and not in {@code common}.</b> ADR-0003 made {@code core → platform}
 * a non-existent Maven dependency, so a wrong import was a compile error. ADR-0012 collapsed the
 * reactor to one module and that guarantee went with it; the ArchUnit rule
 * {@code ArchitectureTest#coreMustNotDependOnPlatform} is now the only automated check left.
 * {@code common} froze at the end of Day 1 and its {@code MarketDataProvider} port describes the
 * raw HTTP provider, not the cache-then-database fallback chain valuation actually needs — so
 * the port is declared by its consumer instead, and {@code MarketDataPriceLookupAdapter} is the
 * one class permitted to bridge it.
 *
 * <p>Implementations never throw on an upstream failure and never return a price dated after the
 * date asked for: forward-filling from a future price would silently invent history
 * (TEST_PLAN.md §4.3).
 */
public interface PriceLookup {

    /**
     * The price for {@code date}, or the most recent one before it, or empty when nothing is
     * known anywhere. Never a price dated after {@code date}.
     */
    Optional<PricePoint> priceOn(long instrumentId, LocalDate date);

    /**
     * Every stored close up to and including {@code to}, per instrument, ascending by date —
     * one of ADR-0010's three flat queries, which {@code PerformanceService} then folds in
     * memory. Deliberately unbounded below: the fold needs the row immediately before the
     * requested range to forward-fill its first day.
     */
    Map<Long, NavigableMap<LocalDate, BigDecimal>> seriesUpTo(Collection<Long> instrumentIds, LocalDate to);
}
