package com.protify.portfolio.valuation;

import com.protify.portfolio.common.enums.CurrencyCode;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * day-5-dev-A.md D5-A1 / BACKLOG PM-18. Every {@code *Pct} field is a percentage at
 * {@link com.protify.portfolio.common.money.MoneyUtils#MONEY_SCALE} (four decimal places), so
 * {@code 10.2702} means 10.2702%, matching {@code MoneyUtils.pctChange}'s convention.
 *
 * <p>{@code timeWeightedReturnPct} and {@code simpleReturnPct} are both reported <b>on purpose</b>.
 * They answer different questions and, for any portfolio the user has paid money into, they
 * disagree loudly: the simple figure credits the user's own deposits as investment performance,
 * the time-weighted one does not. Showing them side by side is the point of the panel.
 *
 * <p>Nullability is meaning, not omission:
 * <ul>
 *   <li>{@code simpleReturnPct} — {@code null} when the window opened at a value of zero, the
 *       same rule as {@code MoneyUtils.pctChange} (TEST_PLAN.md §4.2)</li>
 *   <li>{@code annualisedReturnPct} — {@code null} for a single-day window (there is no elapsed
 *       year to project onto) and when the portfolio's growth factor is negative</li>
 *   <li>{@code bestDay}/{@code worstDay} — {@code null} when the window holds fewer than two
 *       points, so there is no day-over-day move at all</li>
 *   <li>{@code volatilityPct} — {@code null} with fewer than two daily returns, because a sample
 *       standard deviation of one observation is undefined, not zero</li>
 * </ul>
 */
public record AnalyticsResult(
        long portfolioId,
        CurrencyCode currency,
        LocalDate from,
        LocalDate to,
        /** Elapsed days between the first and last point actually computed, which is not
         *  {@code to − from} when the portfolio's history starts inside the window. */
        long days,
        BigDecimal timeWeightedReturnPct,
        BigDecimal annualisedReturnPct,
        BigDecimal simpleReturnPct,
        BigDecimal netContributions,
        Drawdown maxDrawdown,
        DayReturn bestDay,
        DayReturn worstDay,
        BigDecimal volatilityPct) {
}
