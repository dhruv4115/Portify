package com.protify.portfolio.fx;

import com.protify.portfolio.common.enums.CurrencyCode;
import com.protify.portfolio.common.money.Money;

import java.math.BigDecimal;
import java.time.LocalDate;

/** The shape valuation calls tomorrow (hand-off, day-2-dev-B.md): {@code convert} returns both
 * the converted value and the {@code asOf} date the rate actually applies to. */
public record ConversionResult(BigDecimal amount, CurrencyCode currency, LocalDate asOf) {

    public static ConversionResult identity(Money money, LocalDate asOf) {
        return new ConversionResult(money.amount(), money.currency(), asOf);
    }
}
