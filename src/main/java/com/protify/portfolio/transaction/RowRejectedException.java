package com.protify.portfolio.transaction;

/**
 * A bulk import rejected because of one specific transaction in the batch, identified by its
 * 0-based position in the submitted list.
 *
 * <p>It carries an index rather than a line number because the domain has never heard of CSV: the
 * caller that knows which file line the command came from is the one that translates it. That
 * separation is what lets the same import path serve a future JSON bulk endpoint without either
 * side learning about the other's format.
 *
 * <p>Deliberately not a {@code DomainException}: it must never reach {@code GlobalExceptionHandler}
 * as a bare 4xx. The importer catches it and folds it into the import summary, which can say
 * <em>which</em> row failed — a plain 422 cannot.
 */
public final class RowRejectedException extends RuntimeException {

    private final int index;

    public RowRejectedException(int index, String message) {
        super(message);
        this.index = index;
    }

    /** 0-based position in the list handed to {@link TransactionImportService#importAll}. */
    public int index() {
        return index;
    }
}
