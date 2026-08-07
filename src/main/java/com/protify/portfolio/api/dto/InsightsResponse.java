package com.protify.portfolio.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * API_CONTRACT.md §18.
 *
 * <p>{@code engine} is {@code AI_GENERATED} or {@code RULE_BASED}, and is never omitted or
 * softened. A generated sentence and a deterministic one are different kinds of claim, and the
 * reader is entitled to know which they are looking at — the badge in the UI is the point of
 * this field, not decoration on it.
 *
 * <p>The wire value is {@code AI_GENERATED} while the domain enum is
 * {@link com.protify.portfolio.insights.Engine#LLM}: "LLM" names the implementation, which is
 * the right word inside the service and the wrong one in a product surface that may later be
 * served by something that is not one. {@code InsightsMapper} is the single place that
 * translation happens.
 *
 * <p>{@code variants} carries every reading that was produced, so a client can offer the two
 * side by side; {@code engine}/{@code summary}/{@code highlights} mirror {@code variants[0]},
 * the one to show by default. The duplication is deliberate — it keeps every existing consumer
 * of this shape working unchanged, and it means a client that does not care about the
 * comparison never has to learn about the array.
 *
 * <p><b>Two variants only when there are genuinely two.</b> When the LLM path was off, timed
 * out or failed, the rule-based summary <em>is</em> the answer, and the response carries it
 * alone. Padding the array to two so the UI always has a toggle would be offering a choice
 * between a sentence and itself.
 */
public record InsightsResponse(
        long portfolioId,
        Instant generatedAt,
        String engine,
        String summary,
        List<InsightsHighlightDto> highlights,
        String disclaimer,
        List<InsightsVariantDto> variants) {

    public InsightsResponse {
        highlights = highlights == null ? List.of() : List.copyOf(highlights);
        variants = variants == null ? List.of() : List.copyOf(variants);
    }
}
