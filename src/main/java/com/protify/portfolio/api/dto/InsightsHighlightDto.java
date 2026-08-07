package com.protify.portfolio.api.dto;

/**
 * API_CONTRACT.md §18's {@code highlights[]} entry. {@code type} and {@code severity} stay
 * strings rather than becoming enums: they originate in {@code services/insights/main.py}, a
 * separately deployed service, and a new highlight category shipped there must render as
 * itself rather than fail Jackson deserialisation on the way through this one.
 */
public record InsightsHighlightDto(String type, String severity, String message) {
}
