package com.protify.portfolio.valuation;

import com.protify.portfolio.common.enums.CurrencyCode;
import com.protify.portfolio.common.money.MoneyUtils;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One row of {@code portfolio_valuation_daily} — a completed day's valuation, memoised.
 *
 * <p>A snapshot is a <b>cache over</b> the fold, never the source of truth (ADR-0010). It exists
 * only so a 365-day chart does not re-price 365 days of positions on every request, and it is
 * invalidated by the same event that makes it wrong — exactly the relationship {@code txn} has
 * with {@code holding}.
 *
 * <p>{@link #toPoint()} and {@link #of} are inverses. That is deliberate and it is what
 * {@code ValuationSnapshotIT} asserts: a day served from this table must be indistinguishable
 * from the same day folded live, down to {@code filled} and the {@code asOf} dates, or the chart
 * changes shape depending on whether the cache happened to be warm.
 */
public record ValuationSnapshot(
        long portfolioId,
        LocalDate date,
        CurrencyCode currency,
        BigDecimal marketValue,
        BigDecimal costBasis,
        BigDecimal cashBalance,
        BigDecimal unrealisedPnl,
        boolean filled,
        LocalDate priceAsOf,
        LocalDate rateAsOf) {

    /** {@code totalValue} is not stored: it is {@code marketValue + cashBalance}, and both are
     * {@code DECIMAL(19,4)}, so recomputing it here is exact rather than approximate. */
    public PerformancePoint toPoint() {
        return new PerformancePoint(date, marketValue, costBasis, cashBalance,
                MoneyUtils.money(marketValue.add(cashBalance)), unrealisedPnl, filled);
    }

    public static ValuationSnapshot of(long portfolioId, CurrencyCode currency, PerformancePoint point,
            LocalDate priceAsOf, LocalDate rateAsOf) {
        return new ValuationSnapshot(portfolioId, point.date(), currency, point.marketValue(),
                point.costBasis(), point.cashBalance(), point.unrealisedPnl(), point.filled(),
                priceAsOf, rateAsOf);
    }
}
