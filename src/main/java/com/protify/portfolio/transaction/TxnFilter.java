package com.protify.portfolio.transaction;

import com.protify.portfolio.common.enums.TransactionType;
import java.time.LocalDate;

/** Mirrors the filter dimensions in API_CONTRACT.md §7 — every field optional (all-{@code null} = no filter). */
public record TxnFilter(TransactionType type, String symbol, LocalDate from, LocalDate to, String q) {

    /**
     * Blank is not a filter. A UI search box that has been typed into and cleared again sends
     * {@code q=}, which must mean "no text filter" rather than "match only the empty string" —
     * normalising here keeps that decision out of both the controller and the SQL.
     */
    public TxnFilter {
        q = (q == null || q.isBlank()) ? null : q.trim();
        symbol = (symbol == null || symbol.isBlank()) ? null : symbol.trim();
    }

    /** The pre-{@code q} shape, kept so the existing four-dimension callers read unchanged. */
    public TxnFilter(TransactionType type, String symbol, LocalDate from, LocalDate to) {
        this(type, symbol, from, to, null);
    }

    public static TxnFilter none() {
        return new TxnFilter(null, null, null, null, null);
    }
}
