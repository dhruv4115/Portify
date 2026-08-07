package com.protify.portfolio.portfolio;

import com.protify.portfolio.common.enums.CurrencyCode;
import java.time.Instant;

/** Mirrors the {@code portfolio} table. */
public record Portfolio(
        long id,
        long userId,
        String name,
        CurrencyCode baseCurrency,
        Instant createdAt,
        Instant updatedAt) {
}
