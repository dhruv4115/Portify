package com.protify.portfolio.marketdata;

import com.protify.portfolio.common.enums.CurrencyCode;
import com.protify.portfolio.common.enums.PriceSource;

import java.time.LocalDate;

/**
 * D2-B2 — the shape valuation calls tomorrow (hand-off, day-2-dev-B.md): {@code priceFor}
 * returns both the price and its {@code asOf} date, so a stale fallback is visible data, not a
 * silent lie about freshness.
 */
public record PriceResult(java.math.BigDecimal price, CurrencyCode currency, LocalDate asOf, PriceSource source) {
}
