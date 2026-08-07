package com.protify.portfolio.valuation.spi;

import com.protify.portfolio.common.enums.CurrencyCode;
import com.protify.portfolio.common.money.Money;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;

/**
 * How the valuation layer converts between currencies, without knowing that {@code fx} exists.
 * The FX twin of {@link PriceLookup} — see that interface for why the port is declared by its
 * consumer rather than in the frozen {@code common} package.
 *
 * <p>Implementations never hard-code a rate and never return a rate dated after the date asked
 * for, for the same reason {@link PriceLookup} never returns a future price.
 */
public interface FxConversion {

    /** {@code money} expressed in {@code target} using the rate on {@code on} (or the most
     * recent one before it), or empty when no rate can be resolved at all. */
    Optional<ConvertedMoney> convert(Money money, CurrencyCode target, LocalDate on);

    /** The bare {@code from → to} rate on {@code on}, for the cost-basis fold, which converts at
     * each transaction's own trade date rather than at a single valuation date (ADR-0011). */
    Optional<BigDecimal> rate(CurrencyCode from, CurrencyCode to, LocalDate on);

    /**
     * Every stored rate up to and including {@code to}, grouped by date — the second of
     * ADR-0010's three flat queries. Values are the <b>USD-pivot</b> table (ADR-0011): the key
     * is the quote currency and {@code USD} never appears, since it is 1 against itself by
     * definition. A cross rate is {@code rate(USD→to) ÷ rate(USD→from)}.
     */
    NavigableMap<LocalDate, Map<CurrencyCode, BigDecimal>> ratesUpTo(LocalDate to);
}
