package com.protify.portfolio.insights;

/** D5-B2 — matches the FastAPI service's response `highlights[]` shape exactly
 * (services/insights/main.py `Highlight`). */
public record Highlight(String type, String severity, String message) {
}
