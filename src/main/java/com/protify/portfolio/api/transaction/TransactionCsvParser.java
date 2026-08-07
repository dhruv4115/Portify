package com.protify.portfolio.api.transaction;

import com.protify.portfolio.api.dto.ImportRowErrorDto;
import com.protify.portfolio.api.error.FieldViolation;
import com.protify.portfolio.api.error.RequestValidationException;
import com.protify.portfolio.common.enums.CurrencyCode;
import com.protify.portfolio.common.enums.TransactionType;
import com.protify.portfolio.transaction.RecordTransactionCommand;
import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Turns an uploaded CSV into the same {@link RecordTransactionCommand}s the JSON endpoint builds,
 * so an imported transaction and a hand-entered one are indistinguishable from the row they
 * produce onwards — one write path, one set of domain rules, no second definition of what a
 * transaction is (API_CONTRACT.md §8.1).
 *
 * <p><b>Every row is parsed, not just up to the first bad one.</b> Someone fixing a broker export
 * wants the whole list of what is wrong with it, once, rather than one problem per upload.
 *
 * <p>The column set is the one {@code ExportButtons.tsx} writes, so an export re-imports without
 * being edited: the four columns it cannot use ({@code Total (native)}, {@code Total (base)}, and
 * anything else) are ignored rather than rejected, because they are <em>derived</em> — recomputing
 * them from price, quantity and fees is the only way they can be trusted, and honouring them would
 * let a hand-edited total contradict the numbers it is supposed to be the sum of.
 *
 * <p>Headers are matched case- and punctuation-insensitively against a small alias set, so
 * {@code Date}, {@code executedAt} and {@code Execution Date} are the same column. Order does not
 * matter. That is not indulgence: a CSV assembled by hand or exported from a broker will not
 * happen to use this app's capitalisation, and rejecting it for that is a rejection the user
 * cannot act on.
 */
@Component
public class TransactionCsvParser {

    /**
     * The whole file is applied in one database transaction and one in-memory projection, so the
     * cap is about bounding a single request's memory and lock hold time, not about the algorithm
     * — 2,000 rows is far more than a personal ledger and still a fraction of a second of work.
     */
    static final int MAX_ROWS = 2_000;

    /** Mirrors {@code CreateTransactionRequest}'s {@code @Size(max = 500)} — the {@code txn.note}
     * column width. A CSV must not be a way past a bound the JSON endpoint enforces. */
    private static final int MAX_NOTE_LENGTH = 500;

    private static final String DATE = "date";
    private static final String TYPE = "type";
    private static final String SYMBOL = "symbol";
    private static final String QUANTITY = "quantity";
    private static final String PRICE = "price";
    private static final String CURRENCY = "currency";
    private static final String FEES = "fees";
    private static final String NOTE = "note";

    private static final Set<String> REQUIRED_COLUMNS = new LinkedHashSet<>(List.of(DATE, TYPE, PRICE, CURRENCY));

    /** normalised header → canonical column. */
    private static final Map<String, String> ALIASES = Map.ofEntries(
            Map.entry("date", DATE),
            Map.entry("executedat", DATE),
            Map.entry("executedon", DATE),
            Map.entry("executiondate", DATE),
            Map.entry("tradedate", DATE),
            Map.entry("type", TYPE),
            Map.entry("txntype", TYPE),
            Map.entry("transactiontype", TYPE),
            Map.entry("symbol", SYMBOL),
            Map.entry("ticker", SYMBOL),
            Map.entry("instrument", SYMBOL),
            Map.entry("quantity", QUANTITY),
            Map.entry("qty", QUANTITY),
            Map.entry("units", QUANTITY),
            Map.entry("shares", QUANTITY),
            Map.entry("price", PRICE),
            Map.entry("amount", PRICE),
            Map.entry("unitprice", PRICE),
            Map.entry("currency", CURRENCY),
            Map.entry("ccy", CURRENCY),
            Map.entry("fees", FEES),
            Map.entry("fee", FEES),
            Map.entry("commission", FEES),
            Map.entry("charges", FEES),
            Map.entry("note", NOTE),
            Map.entry("notes", NOTE),
            Map.entry("memo", NOTE),
            Map.entry("description", NOTE));

    /**
     * One data row that parsed cleanly, paired with the file line it came from so a domain
     * rejection later on can still be reported against the right line.
     */
    public record ParsedRow(int line, RecordTransactionCommand command) {
    }

    /**
     * The result of reading a whole file: the rows that parsed and the rows that did not. Both,
     * always — {@code rows} is what <em>would</em> be imported if {@code errors} were empty, and
     * the caller imports nothing while it is not.
     */
    public record ParseResult(int totalRows, List<ParsedRow> rows, List<ImportRowErrorDto> errors) {
        public ParseResult {
            rows = List.copyOf(rows);
            errors = List.copyOf(errors);
        }

        public boolean hasErrors() {
            return !errors.isEmpty();
        }

        public List<RecordTransactionCommand> commands() {
            return rows.stream().map(ParsedRow::command).toList();
        }
    }

    /**
     * @throws RequestValidationException when the file as a whole is unusable — empty, headerless,
     *         missing a column every row needs, or longer than {@link #MAX_ROWS}. These are 400s
     *         rather than row errors because there are no rows to report them against.
     */
    public ParseResult parse(String text) {
        List<CsvReader.Record> records = CsvReader.read(text).stream()
                .filter(record -> !record.isBlank())
                .toList();

        if (records.isEmpty()) {
            throw unusableFile("The file is empty.");
        }

        Map<String, Integer> columns = columnIndexes(records.get(0));
        List<CsvReader.Record> dataRows = records.subList(1, records.size());

        if (dataRows.isEmpty()) {
            throw unusableFile("The file has a header row but no transactions.");
        }
        if (dataRows.size() > MAX_ROWS) {
            throw unusableFile("A file may contain at most %d transactions; this one has %d."
                    .formatted(MAX_ROWS, dataRows.size()));
        }

        List<ParsedRow> rows = new ArrayList<>();
        List<ImportRowErrorDto> errors = new ArrayList<>();
        for (CsvReader.Record record : dataRows) {
            try {
                rows.add(new ParsedRow(record.line(), toCommand(record, columns)));
            } catch (RowParseException ex) {
                errors.add(new ImportRowErrorDto(record.line(), ex.getMessage()));
            }
        }
        return new ParseResult(dataRows.size(), rows, errors);
    }

    private static Map<String, Integer> columnIndexes(CsvReader.Record header) {
        Map<String, Integer> columns = new HashMap<>();
        List<String> fields = header.fields();
        for (int i = 0; i < fields.size(); i++) {
            String canonical = ALIASES.get(normaliseHeader(fields.get(i)));
            // First occurrence wins, so a duplicated column cannot silently shadow the one the
            // rest of the file was written against.
            if (canonical != null) {
                columns.putIfAbsent(canonical, i);
            }
        }

        List<String> missing = REQUIRED_COLUMNS.stream().filter(column -> !columns.containsKey(column)).toList();
        if (!missing.isEmpty()) {
            throw unusableFile("The header row is missing required column(s): %s. Expected at least: %s."
                    .formatted(String.join(", ", missing), String.join(", ", REQUIRED_COLUMNS)));
        }
        return columns;
    }

    /**
     * A 400 against the {@code file} part, not a row error: nothing here is wrong with a
     * <em>transaction</em>, so there is no line to point at and no partial result worth returning.
     */
    private static RequestValidationException unusableFile(String message) {
        return new RequestValidationException("/errors/invalid-import-file", message,
                List.of(new FieldViolation("file", message)));
    }

    /** Lower-cased with everything that is not a letter or digit removed, so {@code "Executed At"},
     * {@code "executed_at"} and {@code "executedAt"} all collapse to the same key. */
    private static String normaliseHeader(String header) {
        return header.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private RecordTransactionCommand toCommand(CsvReader.Record record, Map<String, Integer> columns) {
        TransactionType type = type(value(record, columns, TYPE));
        CurrencyCode currency = currency(value(record, columns, CURRENCY));
        String symbol = value(record, columns, SYMBOL);

        BigDecimal quantity = decimal(value(record, columns, QUANTITY), QUANTITY, 13, 6, BigDecimal.ZERO);
        BigDecimal price = decimal(value(record, columns, PRICE), PRICE, 15, 4, null);
        BigDecimal fees = decimal(value(record, columns, FEES), FEES, 15, 4, BigDecimal.ZERO);

        String note = value(record, columns, NOTE);
        if (note != null && note.length() > MAX_NOTE_LENGTH) {
            throw new RowParseException("note must be at most %d characters.".formatted(MAX_NOTE_LENGTH));
        }

        return new RecordTransactionCommand(
                symbol, type, quantity, price, fees, currency, executedAt(value(record, columns, DATE)), note);
    }

    /** {@code null} for a column that is absent, empty, or entirely whitespace — a blank cell and
     * a missing column mean the same thing to every caller here. */
    private static String value(CsvReader.Record record, Map<String, Integer> columns, String column) {
        Integer index = columns.get(column);
        if (index == null || index >= record.fields().size()) {
            return null;
        }
        String raw = record.fields().get(index).strip();
        return raw.isEmpty() ? null : raw;
    }

    private static TransactionType type(String raw) {
        if (raw == null) {
            throw new RowParseException("type is required.");
        }
        try {
            return TransactionType.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new RowParseException("type must be one of %s, but was \"%s\"."
                    .formatted(names(TransactionType.values()), raw));
        }
    }

    private static CurrencyCode currency(String raw) {
        if (raw == null) {
            throw new RowParseException("currency is required.");
        }
        try {
            return CurrencyCode.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new RowParseException("currency must be one of %s, but was \"%s\"."
                    .formatted(names(CurrencyCode.values()), raw));
        }
    }

    /**
     * Strict decimal parsing, with the same {@code integer}/{@code fraction} bounds
     * {@code CreateTransactionRequest} declares — an over-precise figure is refused rather than
     * rounded into the column, so the number in the file and the number in the database cannot
     * disagree.
     *
     * <p>No thousands separators and no currency symbols: {@code "1,450.25"} and {@code "1.450,25"}
     * are the same string to different halves of the world, and a parser that guesses is a parser
     * that will eventually book a trade a thousand times too large.
     */
    private static BigDecimal decimal(String raw, String field, int integerDigits, int fractionDigits,
            BigDecimal whenBlank) {
        if (raw == null) {
            if (whenBlank == null) {
                throw new RowParseException(field + " is required.");
            }
            return whenBlank;
        }
        BigDecimal value;
        try {
            value = new BigDecimal(raw);
        } catch (NumberFormatException ex) {
            throw new RowParseException(
                    "%s must be a plain decimal number (no thousands separators or currency symbols), but was \"%s\"."
                            .formatted(field, raw));
        }
        if (value.scale() > fractionDigits
                || (value.precision() - value.scale()) > integerDigits) {
            throw new RowParseException("%s must have at most %d integer digits and %d decimal places."
                    .formatted(field, integerDigits, fractionDigits));
        }
        return value;
    }

    /**
     * Accepts an instant ({@code 2026-06-15T10:15:00Z} — what this app's own export writes), a
     * local date-time, or a bare date. The last two are read as UTC, matching how the add form
     * turns a date input into an instant, so the same day means the same thing however it arrived.
     *
     * <p>A bare date collapses to "now" when midnight UTC on that day is still ahead of the clock.
     * East of UTC, today's date is genuinely in the future at midnight UTC, and rejecting a file
     * for containing today would be a rejection the user cannot do anything about.
     */
    private static Instant executedAt(String raw) {
        if (raw == null) {
            throw new RowParseException("date is required.");
        }
        try {
            return OffsetDateTime.parse(raw).toInstant();
        } catch (DateTimeException ignored) {
            // Not an offset date-time; try the two zone-less forms below.
        }
        try {
            return LocalDateTime.parse(raw).toInstant(ZoneOffset.UTC);
        } catch (DateTimeException ignored) {
            // Not a local date-time either.
        }
        try {
            Instant midnightUtc = LocalDate.parse(raw).atStartOfDay(ZoneOffset.UTC).toInstant();
            Instant now = Instant.now();
            return midnightUtc.isAfter(now) ? now : midnightUtc;
        } catch (DateTimeException ex) {
            throw new RowParseException(
                    "date must be YYYY-MM-DD or an ISO-8601 timestamp (e.g. 2026-06-15T10:15:00Z), but was \"%s\"."
                            .formatted(raw));
        }
    }

    private static String names(Enum<?>[] values) {
        return String.join(", ", java.util.Arrays.stream(values).map(Enum::name).toList());
    }

    /** Internal control flow only: caught per row and turned into an {@link ImportRowErrorDto}, so
     * it never escapes {@link #parse} and never reaches the exception handler. */
    private static final class RowParseException extends RuntimeException {
        private RowParseException(String message) {
            super(message);
        }
    }
}
