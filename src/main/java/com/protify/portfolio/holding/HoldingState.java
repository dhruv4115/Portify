package com.protify.portfolio.holding;

import java.math.BigDecimal;

/**
 * Per-instrument state folded by {@link ProjectionEngine}: three numbers, always in the
 * instrument's native currency regardless of which {@link ProjectionContext} produced them
 * (REFERENCE_DESIGN non-negotiable #13). Sell-to-zero keeps the entry at {@code quantity}
 * zero rather than removing it, so {@code realisedPnl} survives.
 */
public record HoldingState(
        long instrumentId,
        BigDecimal quantity,
        BigDecimal avgCost,
        BigDecimal realisedPnl) {
}
