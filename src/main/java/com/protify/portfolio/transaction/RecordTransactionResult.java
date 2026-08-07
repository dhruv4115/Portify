package com.protify.portfolio.transaction;

import java.util.List;

/** {@code warnings} is always a list, never {@code null} — empty when there is nothing to say
 * (PLAN.md §2.7: a BUY over cash succeeds with a warning rather than being rejected). */
public record RecordTransactionResult(long txnId, List<String> warnings) {

    public RecordTransactionResult {
        warnings = List.copyOf(warnings);
    }
}
