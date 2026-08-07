package com.protify.portfolio.holding;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Output of one {@link ProjectionEngine#project} fold: per-instrument state keyed by
 * {@code instrumentId}, plus the portfolio's cash balance in {@link ProjectionContext#targetCurrency()}.
 * The native run's {@code cashBalance} is never persisted — {@code holding} has no cash column —
 * only the base-currency run's is consumed downstream, by {@code ValuationService} (Day 3).
 */
public record ProjectionResult(Map<Long, HoldingState> holdingsByInstrument, BigDecimal cashBalance) {
}
