package com.protify.portfolio.valuation;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One day's return, for {@link AnalyticsResult}'s best and worst day.
 *
 * <p>{@code absoluteChange} is the <b>market-driven</b> money change — that day's closing value
 * minus its external cash flow minus the previous close — so it agrees in sign and story with
 * {@code returnPct}. Reporting the raw difference between two closes instead would make the day
 * a user deposited ₹100,000 the best day the portfolio ever had.
 */
public record DayReturn(LocalDate date, BigDecimal returnPct, BigDecimal absoluteChange) {
}
