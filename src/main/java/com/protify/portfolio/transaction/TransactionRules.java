package com.protify.portfolio.transaction;

import com.protify.portfolio.common.enums.TransactionType;
import com.protify.portfolio.common.error.ValidationException;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;

/**
 * The shape rules a transaction must satisfy before anything looks at the ledger: which types
 * carry an instrument, which carry a quantity, and that nothing is dated after now.
 *
 * <p>Extracted from {@link TransactionService} when bulk import arrived. Both write paths — one
 * transaction from a form, two thousand from a CSV — have to enforce exactly the same rules, and
 * two copies of "a DEPOSIT must not name a symbol" is precisely the kind of pair that drifts:
 * the copy nobody is looking at becomes the hole. There is one copy, here.
 *
 * <p>Rules that depend on the ledger's <em>state</em> — a SELL exceeding the holding, a symbol
 * that does not exist, a currency that is not the instrument's — are not here. They need the
 * portfolio's history and belong to the projection, which is where they are enforced.
 */
public final class TransactionRules {

    private static final Set<TransactionType> REQUIRES_POSITIVE_QUANTITY =
            EnumSet.of(TransactionType.BUY, TransactionType.SELL, TransactionType.DIVIDEND);
    private static final Set<TransactionType> REQUIRES_ZERO_QUANTITY =
            EnumSet.of(TransactionType.DEPOSIT, TransactionType.WITHDRAWAL, TransactionType.FEE);
    private static final Set<TransactionType> REQUIRES_SYMBOL =
            EnumSet.of(TransactionType.BUY, TransactionType.SELL, TransactionType.DIVIDEND, TransactionType.FEE);
    private static final Set<TransactionType> FORBIDS_SYMBOL =
            EnumSet.of(TransactionType.DEPOSIT, TransactionType.WITHDRAWAL);
    static final Set<TransactionType> AFFECTS_HOLDING =
            EnumSet.of(TransactionType.BUY, TransactionType.SELL);

    private TransactionRules() {
    }

    /** @throws ValidationException — a 422, carrying a sentence written for the person who typed
     *          (or exported) the transaction. */
    public static void validate(RecordTransactionCommand command) {
        TransactionType type = command.txnType();

        if (REQUIRES_SYMBOL.contains(type) && command.symbol() == null) {
            throw new ValidationException("validation-failed", "A symbol is required for a " + type + " transaction.");
        }
        if (FORBIDS_SYMBOL.contains(type) && command.symbol() != null) {
            throw new ValidationException("validation-failed", "A " + type + " transaction must not reference an instrument.");
        }
        if (REQUIRES_POSITIVE_QUANTITY.contains(type) && command.quantity().signum() <= 0) {
            throw new ValidationException("validation-failed", "Quantity must be positive for a " + type + " transaction.");
        }
        if (REQUIRES_ZERO_QUANTITY.contains(type) && command.quantity().signum() != 0) {
            throw new ValidationException("validation-failed", "Quantity must be zero for a " + type + " transaction.");
        }
        if (command.quantity().signum() < 0) {
            throw new ValidationException("validation-failed", "Quantity must not be negative.");
        }
        if (command.executedAt().isAfter(Instant.now())) {
            throw new ValidationException("validation-failed", "executedAt must not be in the future.");
        }
    }
}
