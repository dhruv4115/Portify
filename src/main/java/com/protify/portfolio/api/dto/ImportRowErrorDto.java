package com.protify.portfolio.api.dto;

/**
 * One rejected row of a CSV import, addressed the way the person looking at the file in a
 * spreadsheet addresses it: {@code line} is the 1-based physical line in the uploaded file,
 * header included, so "line 14" can be found by scrolling to line 14.
 *
 * <p>That is deliberately not the row's index among the data rows — off-by-one against a header
 * is exactly the confusion this field exists to avoid. {@code line} is 0 for a problem with the
 * file as a whole (a missing header, say) rather than with any one row.
 */
public record ImportRowErrorDto(int line, String message) {
}
