package com.protify.portfolio.holding;

import java.math.BigDecimal;
import java.time.Instant;

/** Mirrors the {@code holding} table — the persisted projection, rebuildable from {@code txn}. */
public record Holding(
        long id,
        long portfolioId,
        long instrumentId,
        BigDecimal quantity,
        BigDecimal avgCost,
        BigDecimal realisedPnl,
        Instant updatedAt) {
}
