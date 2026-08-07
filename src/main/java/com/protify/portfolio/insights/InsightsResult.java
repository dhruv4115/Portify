package com.protify.portfolio.insights;

import java.util.List;

/** The shape Dev C's future controller wraps into `/portfolios/{id}/insights`'s response
 * (API_CONTRACT.md §18) — {@code engine} always says honestly which path produced this. */
public record InsightsResult(String summary, List<Highlight> highlights, Engine engine) {
}
