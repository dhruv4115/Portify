package com.protify.portfolio.api.transaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.protify.portfolio.api.error.RequestValidationException;
import com.protify.portfolio.common.enums.CurrencyCode;
import com.protify.portfolio.common.enums.TransactionType;
import com.protify.portfolio.transaction.RecordTransactionCommand;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/**
 * The parser has no database and no Spring context — it is a pure function from text to commands,
 * which is exactly why every awkward file shape is cheap to pin down here rather than through the
 * endpoint.
 */
class TransactionCsvParserTest {

    private static final String HEADER = "Date,Type,Symbol,Quantity,Price,Currency,Fees,Note";

    private final TransactionCsvParser parser = new TransactionCsvParser();

    private static String file(String... rows) {
        return HEADER + "\n" + String.join("\n", rows);
    }

    // ---- the happy path ------------------------------------------------------------------

    @Test
    void parsesEveryColumnOfABuy() {
        var result = parser.parse(file("2026-06-15T10:15:00Z,BUY,AAPL,10,150.25,USD,1.99,First tranche"));

        assertThat(result.hasErrors()).isFalse();
        assertThat(result.totalRows()).isEqualTo(1);

        RecordTransactionCommand command = result.commands().get(0);
        assertThat(command.symbol()).isEqualTo("AAPL");
        assertThat(command.txnType()).isEqualTo(TransactionType.BUY);
        assertThat(command.quantity()).isEqualByComparingTo("10");
        assertThat(command.price()).isEqualByComparingTo("150.25");
        assertThat(command.currency()).isEqualTo(CurrencyCode.USD);
        assertThat(command.fees()).isEqualByComparingTo("1.99");
        assertThat(command.executedAt()).isEqualTo(Instant.parse("2026-06-15T10:15:00Z"));
        assertThat(command.note()).isEqualTo("First tranche");
    }

    @Test
    void blankOptionalCellsMeanAbsent() {
        var result = parser.parse(file("2026-06-15,DEPOSIT,,,5000,USD,,"));

        RecordTransactionCommand command = result.commands().get(0);
        assertThat(command.symbol()).isNull();
        assertThat(command.note()).isNull();
        assertThat(command.quantity()).isEqualByComparingTo("0");
        assertThat(command.fees()).isEqualByComparingTo("0");
    }

    @Test
    void readsBackTheFileThisAppExports() {
        // Byte-for-byte the shape ExportButtons.tsx writes, derived totals included — the two
        // columns nothing can use must be ignored, not rejected, or an export cannot be re-imported.
        String exported = "Date,Type,Symbol,Quantity,Price,Currency,Fees,Total (native),Total (base),Note\r\n"
                + "2026-06-15T10:15:00Z,BUY,RELIANCE,10.000000,1450.2500,INR,24.5000,14527.0000,14527.0000,\r\n"
                + "2026-06-16T09:00:00Z,DEPOSIT,,0.000000,5000.0000,INR,0.0000,5000.0000,5000.0000,\r\n";

        var result = parser.parse(exported);

        assertThat(result.hasErrors()).isFalse();
        assertThat(result.commands()).hasSize(2);
        assertThat(result.commands().get(0).symbol()).isEqualTo("RELIANCE");
        assertThat(result.commands().get(1).txnType()).isEqualTo(TransactionType.DEPOSIT);
    }

    // ---- the CSV format itself -----------------------------------------------------------

    @Test
    void keepsQuotedCommasAndQuotesInsideOneField() {
        var result = parser.parse(file(
                "2026-06-15,BUY,AAPL,10,150.25,USD,0,\"Sold half, kept the rest — \"\"core\"\" holding\""));

        assertThat(result.errors()).isEmpty();
        assertThat(result.commands().get(0).note())
                .isEqualTo("Sold half, kept the rest — \"core\" holding");
    }

    @Test
    void keepsANewlineInsideAQuotedField() {
        var result = parser.parse(file("2026-06-15,BUY,AAPL,10,150.25,USD,0,\"line one\nline two\""));

        assertThat(result.errors()).isEmpty();
        assertThat(result.commands().get(0).note()).isEqualTo("line one\nline two");
    }

    @Test
    void toleratesCrlfBomAndBlankLines() {
        var result = parser.parse("﻿" + HEADER + "\r\n\r\n2026-06-15,BUY,AAPL,10,150.25,USD,0,\r\n");

        assertThat(result.hasErrors()).isFalse();
        assertThat(result.commands()).hasSize(1);
    }

    @Test
    void matchesHeadersRegardlessOfCaseSpacingOrSynonym() {
        var result = parser.parse("executed_at,TXN TYPE,Ticker,Units,Unit Price,CCY,Commission,Memo\n"
                + "2026-06-15,buy,aapl,10,150.25,usd,1.00,note");

        assertThat(result.hasErrors()).isFalse();
        assertThat(result.commands().get(0).txnType()).isEqualTo(TransactionType.BUY);
        assertThat(result.commands().get(0).currency()).isEqualTo(CurrencyCode.USD);
    }

    @Test
    void columnOrderDoesNotMatter() {
        var result = parser.parse("Currency,Price,Type,Date\nUSD,5000,DEPOSIT,2026-06-15");

        assertThat(result.hasErrors()).isFalse();
        assertThat(result.commands().get(0).price()).isEqualByComparingTo("5000");
    }

    // ---- per-row errors ------------------------------------------------------------------

    @Test
    void reportsEveryBadRowAtOnceAgainstItsFileLine() {
        var result = parser.parse(file(
                "2026-06-15,BUY,AAPL,10,150.25,USD,0,",       // line 2, fine
                "2026-06-15,PURCHASE,AAPL,10,150.25,USD,0,",  // line 3
                "not-a-date,BUY,AAPL,10,150.25,USD,0,",       // line 4
                "2026-06-15,BUY,AAPL,10,1.450,25,USD,0,"));   // line 5 — comma decimal

        assertThat(result.totalRows()).isEqualTo(4);
        assertThat(result.commands()).hasSize(1);
        assertThat(result.errors()).extracting(error -> error.line()).containsExactly(3, 4, 5);
        assertThat(result.errors().get(0).message()).contains("type must be one of").contains("PURCHASE");
        assertThat(result.errors().get(1).message()).contains("date must be");
    }

    @Test
    void rejectsAThousandsSeparatorRatherThanGuessingWhatItMeans() {
        var result = parser.parse(file("2026-06-15,BUY,AAPL,10,\"1,450.25\",USD,0,"));

        assertThat(result.errors()).hasSize(1);
        assertThat(result.errors().get(0).message()).contains("plain decimal number");
    }

    @Test
    void rejectsMorePrecisionThanTheColumnCanHold() {
        var result = parser.parse(file("2026-06-15,BUY,AAPL,10,150.123456,USD,0,"));

        assertThat(result.errors()).hasSize(1);
        assertThat(result.errors().get(0).message()).contains("decimal places");
    }

    @Test
    void rejectsARowMissingARequiredValue() {
        var result = parser.parse(file("2026-06-15,BUY,AAPL,10,,USD,0,"));

        assertThat(result.errors()).hasSize(1);
        assertThat(result.errors().get(0).message()).isEqualTo("price is required.");
    }

    @Test
    void rejectsANoteLongerThanTheColumn() {
        var result = parser.parse(file("2026-06-15,BUY,AAPL,10,150.25,USD,0," + "x".repeat(501)));

        assertThat(result.errors()).hasSize(1);
        assertThat(result.errors().get(0).message()).contains("at most 500 characters");
    }

    // ---- dates ---------------------------------------------------------------------------

    @Test
    void readsABareDateAsMidnightUtc() {
        var result = parser.parse(file("2020-03-09,DEPOSIT,,,100,USD,,"));

        assertThat(result.commands().get(0).executedAt())
                .isEqualTo(LocalDate.of(2020, 3, 9).atStartOfDay(ZoneOffset.UTC).toInstant());
    }

    @Test
    void collapsesTodayToNowSoAnEasternTimezoneCanStillImportToday() {
        // Midnight UTC on today's date is still ahead of the clock east of UTC, and the domain
        // refuses a future executedAt. Rejecting a file for containing today would be unfixable.
        String today = LocalDate.now(ZoneOffset.UTC).plusDays(1).toString();

        var result = parser.parse(file(today + ",DEPOSIT,,,100,USD,,"));

        assertThat(result.errors()).isEmpty();
        assertThat(result.commands().get(0).executedAt()).isBeforeOrEqualTo(Instant.now());
    }

    // ---- the file as a whole (400s, not row errors) ---------------------------------------

    @Test
    void rejectsAFileWithNoHeaderColumnsItRecognises() {
        assertThatThrownBy(() -> parser.parse("alpha,beta\n1,2"))
                .isInstanceOf(RequestValidationException.class)
                .hasMessageContaining("missing required column");
    }

    @Test
    void rejectsAnEmptyFile() {
        assertThatThrownBy(() -> parser.parse("   \n\n"))
                .isInstanceOf(RequestValidationException.class)
                .hasMessageContaining("empty");
    }

    @Test
    void rejectsAHeaderWithNoRowsUnderIt() {
        assertThatThrownBy(() -> parser.parse(HEADER + "\n"))
                .isInstanceOf(RequestValidationException.class)
                .hasMessageContaining("no transactions");
    }

    @Test
    void rejectsAFileLongerThanTheRowCap() {
        StringBuilder csv = new StringBuilder(HEADER);
        for (int i = 0; i <= TransactionCsvParser.MAX_ROWS; i++) {
            csv.append("\n2026-06-15,DEPOSIT,,,1,USD,,");
        }

        assertThatThrownBy(() -> parser.parse(csv.toString()))
                .isInstanceOf(RequestValidationException.class)
                .hasMessageContaining("at most " + TransactionCsvParser.MAX_ROWS);
    }

    @Test
    void aRowShorterThanTheHeaderIsTreatedAsBlankTrailingCells() {
        // A hand-edited file often loses its trailing empty cells; every required column is still
        // present, so there is nothing to complain about.
        var result = parser.parse("Date,Type,Price,Currency,Note\n2026-06-15,DEPOSIT,100,USD");

        assertThat(result.hasErrors()).isFalse();
        assertThat(result.commands().get(0).note()).isNull();
        assertThat(result.commands().get(0).price()).isEqualByComparingTo(new BigDecimal("100"));
    }
}
