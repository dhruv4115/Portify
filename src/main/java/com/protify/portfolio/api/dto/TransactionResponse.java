package com.protify.portfolio.api.dto;

import com.protify.portfolio.common.enums.TransactionType;
import java.time.Instant;
import java.util.List;

/**
 * API_CONTRACT.md §7 (list) and §8 (the 201 body, which adds {@code warnings}). One shape for
 * both, so a transaction rendered straight after being created is byte-identical to the same
 * transaction re-read from the list.
 *
 * <p>{@code price} and {@code fees} are in the instrument's <b>native</b> currency and
 * {@code totalBase} is in the portfolio's <b>base</b> currency (§0.3). {@code fxRateApplied} is
 * the rate on {@code executedAt}, <em>not</em> today's — that is what makes cost basis
 * economically correct, and it is {@code null} when native and base are the same currency.
 * {@code totalBase} is {@code null} only in the one case where no rate for that date could be
 * resolved from any source; the transaction itself is unaffected.
 *
 * <p>{@code warnings} is never {@code null} — {@code []} when there is nothing to say (PLAN.md
 * §2.7: a BUY exceeding available cash is a warning on a 201, not a rejection).
 */
public record TransactionResponse(
        long id,
        TransactionType type,
        InstrumentResponse instrument,
        String quantity,
        MoneyDto price,
        MoneyDto fees,
        MoneyDto totalNative,
        MoneyDto totalBase,
        String fxRateApplied,
        Instant executedAt,
        String note,
        List<String> warnings) {

    public TransactionResponse {
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
