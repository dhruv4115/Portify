package com.protify.portfolio.api.transaction;

import java.util.ArrayList;
import java.util.List;

/**
 * An RFC 4180 reader, hand-written because {@code pom.xml} carries no CSV library and this is
 * about eighty lines of state machine — a dependency is a bigger commitment than the code it
 * replaces at this size.
 *
 * <p>It is a real tokeniser rather than {@code line.split(",")} for one reason: a quoted field may
 * contain a comma, a quote (doubled) <em>or a newline</em>. A note reading {@code "sold half,
 * kept the rest"} is a single field, and splitting on commas turns it into two, silently shifting
 * every column after it by one. Exports from this very app quote exactly that way
 * ({@code lib/csv.ts}), so a file this app produced has to be a file this app can read back.
 *
 * <p>Each returned {@link Record} carries the 1-based physical line it started on, so an error
 * can be reported against the line the user sees in their spreadsheet even when an earlier field
 * spanned several lines.
 */
final class CsvReader {

    /** One parsed record: its fields, and the file line its first character sat on. */
    record Record(int line, List<String> fields) {
        Record {
            fields = List.copyOf(fields);
        }

        boolean isBlank() {
            return fields.stream().allMatch(field -> field.isBlank());
        }
    }

    private CsvReader() {
    }

    /**
     * Splits {@code text} into records. Recognises CRLF, LF and CR line endings, and strips a
     * leading UTF-8 byte-order mark — Excel writes one, and left in place it becomes part of the
     * first header's name, so {@code Date} stops matching a column called {@code ﻿Date}.
     */
    static List<Record> read(String text) {
        String input = text.startsWith("﻿") ? text.substring(1) : text;

        List<Record> records = new ArrayList<>();
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;
        int line = 1;
        int recordLine = 1;

        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);

            if (inQuotes) {
                if (c == '"') {
                    // A doubled quote inside a quoted field is one literal quote; a lone one ends
                    // the field.
                    if (i + 1 < input.length() && input.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    if (c == '\n') {
                        line++;
                    }
                    field.append(c);
                }
                continue;
            }

            switch (c) {
                case '"' -> inQuotes = true;
                case ',' -> {
                    fields.add(field.toString());
                    field.setLength(0);
                }
                case '\r', '\n' -> {
                    // Consume the LF of a CRLF pair so it does not open an extra empty record.
                    if (c == '\r' && i + 1 < input.length() && input.charAt(i + 1) == '\n') {
                        i++;
                    }
                    fields.add(field.toString());
                    field.setLength(0);
                    records.add(new Record(recordLine, fields));
                    fields = new ArrayList<>();
                    line++;
                    recordLine = line;
                }
                default -> field.append(c);
            }
        }

        // A file that does not end in a newline still has a last record.
        if (!field.isEmpty() || !fields.isEmpty()) {
            fields.add(field.toString());
            records.add(new Record(recordLine, fields));
        }
        return records;
    }
}
