package com.protify.portfolio.holding;

import com.protify.portfolio.common.enums.CurrencyCode;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Everything {@link ProjectionEngine} needs to know about currency for one fold. FX is applied
 * only to the cash accumulator (ADR-0011: {@code avg_cost}/{@code realisedPnl} stay native no
 * matter what {@code targetCurrency} is), so the native run and the base-currency run are the
 * same code with a different context — see PLAN.md §5.
 */
public record ProjectionContext(CurrencyCode targetCurrency, FxLookup fxLookup) {

    /** {@code (from, on) -> BigDecimal} — a rate, resolved for the given date, never {@code null}. */
    @FunctionalInterface
    public interface FxLookup {
        BigDecimal rate(CurrencyCode from, LocalDate on);
    }

    /** Native run: identity FX, so cash accumulates raw native amounts with no conversion. */
    public static ProjectionContext identity(CurrencyCode currency) {
        return new ProjectionContext(currency, (from, on) -> BigDecimal.ONE);
    }
}
