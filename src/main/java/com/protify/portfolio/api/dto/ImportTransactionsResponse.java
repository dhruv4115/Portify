package com.protify.portfolio.api.dto;

import java.util.List;

/**
 * The outcome of {@code POST /portfolios/{id}/transactions/import} (API_CONTRACT.md §8.1).
 *
 * <p><b>An import that imported nothing is still a 200.</b> The alternative — a 4xx carrying a
 * {@code ProblemDetail} — can say "the file was rejected" but not "rows 3, 9 and 40 were rejected,
 * for these three different reasons", which is the only answer that lets someone fix their file.
 * A non-2xx would also collapse into the client's generic error path and lose the summary
 * entirely. The status says the server processed the upload; {@code imported} and {@code errors}
 * say what came of it. A genuinely unusable upload — empty, no header, over the size cap, not a
 * portfolio you own — is still a 4xx, because there is no per-row story to tell.
 *
 * <p>{@code imported} is all-or-nothing by construction: the rows are applied inside one database
 * transaction, so a file that fails anywhere imports nothing and leaves the ledger exactly as it
 * was. Half an import is not something a person can reconcile against their broker statement.
 *
 * <p>{@code dryRun} echoes the request's flag so a client cannot mistake a validation pass for a
 * write. On a dry run {@code imported} is what <em>would</em> have been written.
 */
public record ImportTransactionsResponse(
        boolean dryRun,
        int totalRows,
        int imported,
        int failed,
        List<ImportRowErrorDto> errors,
        List<String> warnings) {

    public ImportTransactionsResponse {
        errors = errors == null ? List.of() : List.copyOf(errors);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    public static ImportTransactionsResponse rejected(
            boolean dryRun, int totalRows, List<ImportRowErrorDto> errors) {
        return new ImportTransactionsResponse(dryRun, totalRows, 0, errors.size(), errors, List.of());
    }

    public static ImportTransactionsResponse imported(
            boolean dryRun, int totalRows, int imported, List<String> warnings) {
        return new ImportTransactionsResponse(dryRun, totalRows, imported, 0, List.of(), warnings);
    }
}
