package com.protify.portfolio.api.mapper;

import com.protify.portfolio.api.dto.InsightsHighlightDto;
import com.protify.portfolio.api.dto.InsightsResponse;
import com.protify.portfolio.api.dto.InsightsVariantDto;
import com.protify.portfolio.insights.Engine;
import com.protify.portfolio.insights.Highlight;
import com.protify.portfolio.insights.InsightsResult;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * {@link InsightsResult} → API_CONTRACT.md §18's body. The only place the domain's
 * {@link Engine} becomes a wire string, so the two vocabularies cannot drift apart in more
 * than one file.
 */
@Component
public class InsightsMapper {

    /** §18. Fixed text, not model output — a disclaimer the generator could rewrite would not
     * be one. */
    private static final String DISCLAIMER = "Generated commentary. Not investment advice.";

    /** A response with a single variant — the only answer there was. */
    public InsightsResponse toResponse(long portfolioId, InsightsResult result, Instant generatedAt) {
        return toResponse(portfolioId, List.of(result), generatedAt);
    }

    /**
     * @param variants every reading produced for this portfolio, most-preferred first. The first
     *                 is promoted to the top-level {@code engine}/{@code summary}/{@code
     *                 highlights} fields, so a client that knows nothing about variants still
     *                 gets the one it should show — and the array is what a client offering the
     *                 comparison reads instead.
     */
    public InsightsResponse toResponse(long portfolioId, List<InsightsResult> variants, Instant generatedAt) {
        if (variants.isEmpty()) {
            throw new IllegalArgumentException("an insights response needs at least one variant");
        }
        List<InsightsVariantDto> dtos = variants.stream().map(InsightsMapper::toVariant).toList();
        InsightsVariantDto primary = dtos.get(0);

        return new InsightsResponse(
                portfolioId,
                generatedAt,
                primary.engine(),
                primary.summary(),
                primary.highlights(),
                DISCLAIMER,
                dtos);
    }

    private static InsightsVariantDto toVariant(InsightsResult result) {
        List<InsightsHighlightDto> highlights = result.highlights() == null
                ? List.of()
                : result.highlights().stream().map(InsightsMapper::toDto).toList();
        return new InsightsVariantDto(wireName(result.engine()), result.summary(), highlights);
    }

    /**
     * {@code LLM} is the implementation's name for itself; {@code AI_GENERATED} is what the
     * product surface calls it, and what the client's badge switches on. A {@code null} engine
     * — only reachable if the Python service omitted the field — is reported as
     * {@code RULE_BASED} rather than as AI: the honest failure here is to under-claim.
     */
    private static String wireName(Engine engine) {
        return engine == Engine.LLM ? "AI_GENERATED" : "RULE_BASED";
    }

    private static InsightsHighlightDto toDto(Highlight highlight) {
        return new InsightsHighlightDto(highlight.type(), highlight.severity(), highlight.message());
    }
}
