package com.protify.portfolio.api.dto;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * API_CONTRACT.md §0.5. Goes on every response carrying a market value, so the UI can show
 * honest, degraded data instead of failing — {@code stale} is true when either date is more
 * than 3 calendar days behind {@code asOf}. Dev B's caching services are the source of these
 * dates once market data lands; until then, {@code priceAsOf}/{@code rateAsOf} are {@code
 * null} and {@code stale} reflects whether there is anything unpriced to be honest about.
 */
public record DataQualityDto(LocalDate priceAsOf, LocalDate rateAsOf, boolean stale) {

    /** §0.5's threshold. A weekend is two days and a long weekend three, so this is the smallest
     * value that does not paint every Monday amber. */
    private static final int STALE_AFTER_DAYS = 3;

    public static DataQualityDto empty() {
        return new DataQualityDto(null, null, false);
    }

    /**
     * §0.5's rule, applied where the per-instrument dates are actually known: stale when either
     * date is more than three calendar days behind {@code asOf}. A {@code null} date means the
     * value could not be resolved from any source at all, which is the most stale a figure can
     * be, so it counts too.
     */
    public static DataQualityDto of(LocalDate priceAsOf, LocalDate rateAsOf, LocalDate asOf) {
        return new DataQualityDto(priceAsOf, rateAsOf, isStale(priceAsOf, asOf) || isStale(rateAsOf, asOf));
    }

    private static boolean isStale(LocalDate date, LocalDate asOf) {
        return date == null || ChronoUnit.DAYS.between(date, asOf) > STALE_AFTER_DAYS;
    }
}
