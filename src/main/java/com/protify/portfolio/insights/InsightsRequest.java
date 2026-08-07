package com.protify.portfolio.insights;

/** The exact request body {@code services/insights/main.py}'s {@code POST /insights} expects. */
record InsightsRequest(PortfolioSummary portfolioSummary, String horizon, String tone) {
}
