package com.protify.portfolio.api.transaction;

import com.protify.portfolio.api.dto.CreateTransactionRequest;
import com.protify.portfolio.api.dto.ImportRowErrorDto;
import com.protify.portfolio.api.dto.ImportTransactionsResponse;
import com.protify.portfolio.api.dto.PageResponse;
import com.protify.portfolio.api.dto.TransactionResponse;
import com.protify.portfolio.api.error.FieldViolation;
import com.protify.portfolio.api.error.RequestValidationException;
import com.protify.portfolio.api.mapper.TransactionMapper;
import com.protify.portfolio.api.user.CurrentUserResolver;
import com.protify.portfolio.common.enums.TransactionType;
import com.protify.portfolio.support.Page;
import com.protify.portfolio.support.Pageable;
import com.protify.portfolio.transaction.RecordTransactionCommand;
import com.protify.portfolio.transaction.RecordTransactionResult;
import com.protify.portfolio.transaction.RowRejectedException;
import com.protify.portfolio.transaction.TransactionImportService;
import com.protify.portfolio.transaction.TransactionService;
import com.protify.portfolio.transaction.TxnFilter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * The customer's "add" and "remove" (day-3-dev-C.md D3-C1), API_CONTRACT.md §7–§9. Validates,
 * delegates, maps — the domain rules (sell exceeding a holding, unknown symbol, currency
 * mismatch, the negative-cash warning) all live in Dev A's {@link TransactionService} and reach
 * the client through {@code GlobalExceptionHandler}; nothing here inspects them.
 *
 * <p>Every method resolves {@code userId} first and passes it into every call, so a portfolio
 * belonging to someone else is a 404 rather than a 403, and so is one of their transactions
 * addressed through a portfolio path of your own — {@code deleteByIdAndPortfolio} matches on both
 * ids, so it deletes nothing and reports not-found.
 *
 * <p>Ordering is fixed at the contract's default {@code executedAt,desc} (§0.6): the {@code sort}
 * parameter is not accepted, because {@code TransactionRepository} hard-codes that order and it
 * is Dev A's file. Raised for the team rather than answered with a parameter that quietly ignores
 * whatever it is given.
 */
@RestController
@RequestMapping("/portfolios/{portfolioId}/transactions")
@Validated
public class TransactionController {

    /** API_CONTRACT.md §0.6. */
    private static final int MAX_PAGE_SIZE = 100;

    /** Matches {@code txn.note}'s column width — the longest field {@code q} searches, so no
     * legitimate search can need more, and an unbounded term becomes a needless scan. */
    private static final int MAX_QUERY_LENGTH = 500;

    /**
     * 2 MB. {@link TransactionCsvParser#MAX_ROWS} rows of this shape is well under half of it, so
     * anything larger is a file that was never going to import — a spreadsheet saved as XLSX, a
     * whole account statement — and refusing it before reading it into memory is cheaper for
     * everyone than parsing 50 MB to say the same thing.
     */
    private static final long MAX_IMPORT_BYTES = 2L * 1024 * 1024;

    private final TransactionService transactionService;
    private final TransactionQueryService transactionQueryService;
    private final TransactionImportService transactionImportService;
    private final TransactionCsvParser csvParser;
    private final TransactionMapper transactionMapper;
    private final CurrentUserResolver currentUserResolver;

    public TransactionController(
            TransactionService transactionService,
            TransactionQueryService transactionQueryService,
            TransactionImportService transactionImportService,
            TransactionCsvParser csvParser,
            TransactionMapper transactionMapper,
            CurrentUserResolver currentUserResolver) {
        this.transactionService = transactionService;
        this.transactionQueryService = transactionQueryService;
        this.transactionImportService = transactionImportService;
        this.csvParser = csvParser;
        this.transactionMapper = transactionMapper;
        this.currentUserResolver = currentUserResolver;
    }

    @PostMapping
    public ResponseEntity<TransactionResponse> create(
            @PathVariable long portfolioId,
            @Valid @RequestBody CreateTransactionRequest request) {
        long userId = currentUserResolver.resolve().id();

        RecordTransactionCommand command = transactionMapper.toCommand(request);
        RecordTransactionResult result = transactionService.record(userId, portfolioId, command);
        TransactionResponse body = transactionQueryService.describe(
                userId, portfolioId, result.txnId(), command, result.warnings());

        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{txnId}")
                .buildAndExpand(result.txnId())
                .toUri();
        return ResponseEntity.created(location).body(body);
    }

    /**
     * Never 404 for an empty result — a portfolio with no transactions is a normal answer.
     *
     * <p>{@code q} is a free-text term over symbol, instrument name and note. It belongs here
     * rather than in the client because filtering in the browser can only ever see the pages
     * already loaded: a search for a symbol bought two years ago silently returns nothing until
     * the reader has paged back that far. Applied in SQL, "no match" means there is genuinely no
     * match. It composes with the other filters rather than replacing them — {@code type} and
     * {@code q} together narrow, and never widen, the result.
     */
    @GetMapping
    public PageResponse<TransactionResponse> list(
            @PathVariable long portfolioId,
            @RequestParam(required = false) TransactionType type,
            @RequestParam(required = false) String symbol,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) @Size(max = MAX_QUERY_LENGTH) String q,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(MAX_PAGE_SIZE) int size) {
        long userId = currentUserResolver.resolve().id();

        Page<TransactionResponse> result = transactionQueryService.list(
                userId, portfolioId, new TxnFilter(type, symbol, from, to, q), new Pageable(page, size));
        return PageResponse.from(result);
    }

    /**
     * Bulk add from a CSV file (API_CONTRACT.md §8.1) — the counterpart to the export in
     * {@code ExportButtons.tsx}, and readable straight from it: the columns it cannot use are
     * ignored, so a file exported from this app re-imports into another portfolio unedited.
     *
     * <p>There is no matching holdings import, and there should not be. Holdings are a projection
     * of the ledger, not a thing you can state independently of it (README: "transactions are the
     * source of truth, holdings are a projection"). Accepting a holdings file would mean accepting
     * a position whose quantity, average cost and realised P&amp;L nothing in the history explains
     * — the first figure that could not be rebuilt from the ledger, and the end of the property
     * that makes every other number here auditable. Importing the transactions instead recomputes
     * the holdings from them, which is why this endpoint needs no separate "update holdings" step.
     *
     * <p>Returns 200 with a summary rather than a 4xx for a file whose <em>rows</em> are wrong —
     * see {@link ImportTransactionsResponse} for why. A file that is unusable as a file, and a
     * portfolio that is not yours, are still 400 and 404.
     */
    @PostMapping(path = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ImportTransactionsResponse importCsv(
            @PathVariable long portfolioId,
            @RequestPart("file") MultipartFile file,
            @RequestParam(defaultValue = "false") boolean dryRun) {
        long userId = currentUserResolver.resolve().id();

        TransactionCsvParser.ParseResult parsed = csvParser.parse(read(file));
        if (parsed.hasErrors()) {
            // Rows that could not even be parsed are reported without touching the database: there
            // is no point locking a portfolio to apply a batch already known to be incomplete.
            return ImportTransactionsResponse.rejected(dryRun, parsed.totalRows(), parsed.errors());
        }

        try {
            var result = transactionImportService.importAll(userId, portfolioId, parsed.commands(), dryRun);
            return ImportTransactionsResponse.imported(
                    dryRun, parsed.totalRows(), result.imported(), result.warnings());
        } catch (RowRejectedException ex) {
            // One row the ledger cannot accept — an unknown symbol, a currency that is not the
            // instrument's, a SELL with nothing behind it. Nothing was written; the index is
            // translated back into the file line it came from.
            int line = parsed.rows().get(ex.index()).line();
            return ImportTransactionsResponse.rejected(dryRun, parsed.totalRows(),
                    List.of(new ImportRowErrorDto(line, ex.getMessage())));
        }
    }

    /** Reads the upload as UTF-8, refusing anything past {@link #MAX_IMPORT_BYTES} before it is
     * held in memory. A CSV is text; bytes that are not valid UTF-8 become replacement characters
     * and fail as ordinary per-row parse errors, which is a better answer than a 500. */
    private static String read(MultipartFile file) {
        if (file.isEmpty()) {
            throw invalidFile("No file was uploaded, or the file is empty.");
        }
        if (file.getSize() > MAX_IMPORT_BYTES) {
            throw invalidFile("The file must be at most %d MB.".formatted(MAX_IMPORT_BYTES / (1024 * 1024)));
        }
        try {
            return new String(file.getBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw invalidFile("The uploaded file could not be read.");
        }
    }

    private static RequestValidationException invalidFile(String message) {
        return new RequestValidationException("/errors/invalid-import-file", message,
                List.of(new FieldViolation("file", message)));
    }

    @DeleteMapping("/{txnId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long portfolioId, @PathVariable long txnId) {
        long userId = currentUserResolver.resolve().id();
        transactionService.delete(userId, portfolioId, txnId);
    }
}
