package com.protify.portfolio.valuation;

/**
 * The four breakdowns API_CONTRACT.md §13 offers. Lives here rather than in
 * {@code common/enums} because {@code common} froze at the end of Day 1; {@code api} may depend
 * on {@code valuation}, so Dev C's DTO layer can still reach it.
 */
public enum AllocateBy {
    ASSET_TYPE,
    SECTOR,
    /** The one that best demonstrates the multi-currency feature — it shows FX exposure as a
     * percentage of the portfolio (day-4-dev-A.md D4-A4). */
    CURRENCY,
    INSTRUMENT
}
