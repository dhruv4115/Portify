package com.protify.portfolio.transaction;

import com.protify.portfolio.common.enums.CurrencyCode;
import com.protify.portfolio.common.enums.TransactionType;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * Mirrors the {@code txn} table. Plain record, no framework annotations — {@code holding}'s
 * {@code ProjectionEngine} folds a {@code List<Txn>} as a pure function, so this type must stay
 * free of Spring/JDBC dependencies even though it lives next to a repository that isn't.
 * {@code instrumentId} is {@code null} for {@code DEPOSIT}/{@code WITHDRAWAL}.
 */
public record Txn(
        Long id,
        long portfolioId,
        Long instrumentId,
        TransactionType txnType,
        BigDecimal quantity,
        BigDecimal price,
        BigDecimal fees,
        CurrencyCode currency,
        Instant executedAt,
        String note,
        Instant createdAt) {
}
