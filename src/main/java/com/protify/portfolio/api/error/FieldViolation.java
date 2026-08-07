package com.protify.portfolio.api.error;

/**
 * One entry in a {@code ProblemDetail}'s {@code errors[]} array (API_CONTRACT.md §0.7):
 * {@code { "field": "quantity", "message": "must not exceed holding" } }.
 */
public record FieldViolation(String field, String message) {
}
