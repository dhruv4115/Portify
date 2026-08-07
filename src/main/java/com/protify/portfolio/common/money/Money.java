package com.protify.portfolio.common.money;

import com.protify.portfolio.common.enums.CurrencyCode;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * Amount is normalised to {@link MoneyUtils#MONEY_SCALE} in the compact constructor, so it is
 * the only place that decision gets made. {@link #equals} compares by value ({@code compareTo}),
 * not {@link BigDecimal#equals}, so {@code new BigDecimal("100.00")} and
 * {@code new BigDecimal("100.0000")} are the same {@code Money}.
 */
public record Money(BigDecimal amount, CurrencyCode currency) {

    public Money {
        Objects.requireNonNull(amount, "amount must not be null");
        Objects.requireNonNull(currency, "currency must not be null");
        amount = MoneyUtils.money(amount);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Money other)) {
            return false;
        }
        return currency == other.currency && amount.compareTo(other.amount) == 0;
    }

    @Override
    public int hashCode() {
        // amount is always MONEY_SCALE after the compact constructor, so compareTo-equal
        // amounts are also BigDecimal#equals-equal here and this stays consistent with equals.
        return Objects.hash(amount, currency);
    }
}
