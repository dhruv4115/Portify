package com.protify.portfolio.api.dto;

import java.time.LocalDate;

/**
 * One point of the chart (API_CONTRACT.md §12), every amount in the series' currency.
 *
 * <p>{@code filled} means this point's price or FX rate was carried forward from an earlier date
 * — a weekend, a market holiday, or a day the provider had nothing — rather than being fresh for
 * {@code date}. It is not a statement about whether a transaction happened. The UI uses it to
 * draw those segments differently instead of pretending the market traded.
 */
public record PerformancePointDto(
        LocalDate date,
        MoneyDto marketValue,
        MoneyDto costBasis,
        MoneyDto cashBalance,
        MoneyDto totalValue,
        MoneyDto unrealisedPnl,
        boolean filled) {
}
