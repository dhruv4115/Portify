package com.protify.portfolio.valuation;

import com.protify.portfolio.common.enums.TransactionType;
import com.protify.portfolio.common.money.MoneyUtils;
import com.protify.portfolio.holding.ProjectionContext;
import com.protify.portfolio.transaction.Txn;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * External cash flows — {@code DEPOSIT} minus {@code WITHDRAWAL} — per date, in a target
 * currency at each transfer's <b>own</b> trade date (ADR-0011, the same rule
 * {@link ValuationFolder} applies to cost basis).
 *
 * <p>Extracted from {@link PerformanceService}, which needs only the total (its
 * {@code netContributions}), so that {@link AnalyticsService} can use the per-day breakdown for
 * time-weighted return without a second, subtly different definition of "the user's own money".
 * A sign slip or a missed transaction type in one copy and not the other would make the chart
 * and the analytics panel disagree about the same portfolio, which is exactly the class of bug
 * {@link ValuationFolder} and {@link PositionPricer} are shared to prevent.
 *
 * <p>{@code BUY}, {@code SELL}, {@code DIVIDEND} and {@code FEE} are deliberately <b>not</b>
 * flows: they move money between cash and positions, or are a cost of running the portfolio.
 * Only a deposit or a withdrawal changes how much the user has put in, and only those two must
 * be netted out before a return is computed.
 */
public final class CashFlowSeries {

    private CashFlowSeries() {
    }

    /**
     * Net external flow per date, restricted to {@code [from, to]} inclusive. Dates with no
     * transfer are absent rather than mapped to zero, so callers can tell "no flow" from
     * "a deposit and a withdrawal that happened to cancel".
     *
     * @param fxLookup never returns {@code null} ({@link ProjectionContext.FxLookup}'s
     *                 contract), so an unresolvable rate falls back to identity rather than
     *                 dropping a transfer out of the total
     */
    public static NavigableMap<LocalDate, BigDecimal> byDate(
            List<Txn> txns, ProjectionContext.FxLookup fxLookup, LocalDate from, LocalDate to) {

        NavigableMap<LocalDate, BigDecimal> flows = new TreeMap<>();
        for (Txn txn : txns) {
            if (txn.txnType() != TransactionType.DEPOSIT && txn.txnType() != TransactionType.WITHDRAWAL) {
                continue;
            }
            LocalDate date = ValuationFolder.dateOf(txn);
            if (date.isBefore(from) || date.isAfter(to)) {
                continue;
            }
            BigDecimal amount = txn.price().multiply(fxLookup.rate(txn.currency(), date));
            BigDecimal signed = txn.txnType() == TransactionType.DEPOSIT ? amount : amount.negate();
            flows.merge(date, signed, BigDecimal::add);
        }
        return flows;
    }

    /** Deposits minus withdrawals over the whole window, rounded once at the end. */
    public static BigDecimal total(NavigableMap<LocalDate, BigDecimal> flows) {
        BigDecimal total = BigDecimal.ZERO;
        for (BigDecimal flow : flows.values()) {
            total = total.add(flow);
        }
        return MoneyUtils.money(total);
    }
}
