package com.protify.portfolio.api.holding;

import com.protify.portfolio.holding.Holding;
import com.protify.portfolio.instrument.Instrument;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One holding reduced to numbers, between {@link HoldingViewService} (which fetches and computes)
 * and {@code HoldingMapper} (which shapes JSON). It exists so the mapper stays a pure function of
 * its arguments instead of taking a twelve-parameter method, and so the currency of every field
 * is stated once, here, rather than being re-derived at each use.
 *
 * @param lastPrice   the instrument's <b>native</b> currency, {@code null} when no price could be
 *                    resolved from provider, cache or seed
 * @param marketValue the target currency, {@code null} whenever {@code lastPrice} or
 *                    {@code fxRate} is
 * @param costBasis   the target currency, at the FX rate on each contributing trade's own date
 * @param realisedPnl the target currency, same trade-date basis
 * @param fxRate      native → target on {@code asOf}; {@code 1} when those are the same currency,
 *                    {@code null} when unresolvable
 */
public record HoldingValuation(
        Holding holding,
        Instrument instrument,
        BigDecimal lastPrice,
        BigDecimal marketValue,
        BigDecimal costBasis,
        BigDecimal realisedPnl,
        BigDecimal fxRate,
        LocalDate priceAsOf,
        LocalDate rateAsOf) {
}
