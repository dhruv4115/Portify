package com.protify.portfolio.valuation;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * The largest peak-to-trough decline over the reported window, measured on the <b>chain-linked
 * return index</b> rather than on raw portfolio value.
 *
 * <p>That distinction is the same one time-weighted return exists for: a user who withdraws half
 * their portfolio has not suffered a 50% drawdown, and a raw-value measure says they have.
 *
 * <p>{@code declinePct} is negative or zero, never positive. A window that only ever rose reports
 * {@code 0.0000} with {@code null} dates — "there was no drawdown" rather than a zero-width one
 * pinned to an arbitrary day.
 */
public record Drawdown(BigDecimal declinePct, LocalDate peakDate, LocalDate troughDate) {
}
