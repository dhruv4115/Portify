package com.protify.portfolio.valuation;

import java.math.BigDecimal;

/**
 * One wedge of the pie (API_CONTRACT.md §13). {@code value} is in the requested currency;
 * {@code weightPct} is a percentage at {@code MoneyUtils.MONEY_SCALE}, and across a result's
 * slices they sum to exactly 100 — {@link AllocationService} puts the rounding residual on the
 * largest slice so the total is exact rather than merely close.
 */
public record AllocationSlice(
        String key,
        String label,
        BigDecimal value,
        BigDecimal weightPct,
        int instrumentCount) {
}
