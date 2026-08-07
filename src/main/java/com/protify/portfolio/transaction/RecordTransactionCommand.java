package com.protify.portfolio.transaction;

import com.protify.portfolio.common.enums.CurrencyCode;
import com.protify.portfolio.common.enums.TransactionType;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * What {@link TransactionService#record} needs, independent of the DTO Dev C's controller
 * builds from the request body — the service is callable (and testable) without a web layer.
 * {@code symbol} is {@code null} for {@code DEPOSIT}/{@code WITHDRAWAL}, matching {@code txn
 * .instrument_id}'s nullability.
 */
public record RecordTransactionCommand(
        String symbol,
        TransactionType txnType,
        BigDecimal quantity,
        BigDecimal price,
        BigDecimal fees,
        CurrencyCode currency,
        Instant executedAt,
        String note) {

    public RecordTransactionCommand {
        if (fees == null) {
            fees = BigDecimal.ZERO;
        }
    }
}
