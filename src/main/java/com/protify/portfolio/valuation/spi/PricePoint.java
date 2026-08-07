package com.protify.portfolio.valuation.spi;

import com.protify.portfolio.common.enums.CurrencyCode;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A price and the date it actually applies to — the core-side equivalent of
 * {@code marketdata.PriceResult}, so that a platform type never crosses {@link PriceLookup}.
 *
 * <p>{@code asOf} is the date of the row that was really served, which is not necessarily the
 * date that was asked for: a weekend, a market holiday or a provider outage all resolve to an
 * earlier row. Carrying it is what lets the valuation layer report staleness instead of
 * silently lying about freshness.
 */
public record PricePoint(BigDecimal price, CurrencyCode currency, LocalDate asOf) {
}
