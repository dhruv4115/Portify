package com.protify.portfolio.api.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.protify.portfolio.api.dto.InsightsResponse;
import com.protify.portfolio.insights.Engine;
import com.protify.portfolio.insights.Highlight;
import com.protify.portfolio.insights.InsightsResult;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The domain says {@code LLM}; the wire says {@code AI_GENERATED}. This is the one place that
 * translation happens, so it is the one place worth pinning it down — the client's badge
 * switches on the wire value, and getting it wrong mislabels generated text as deterministic.
 */
class InsightsMapperTest {

    private final InsightsMapper mapper = new InsightsMapper();

    @Test
    void llmBecomesAiGeneratedOnTheWire() {
        InsightsResult result = new InsightsResult("Up 10%.", List.of(), Engine.LLM);

        InsightsResponse response = mapper.toResponse(7L, result, Instant.EPOCH);

        assertThat(response.engine()).isEqualTo("AI_GENERATED");
    }

    @Test
    void ruleBasedIsReportedHonestlyRatherThanDressedUp() {
        InsightsResult result = new InsightsResult("Nothing notable.", List.of(), Engine.RULE_BASED);

        assertThat(mapper.toResponse(7L, result, Instant.EPOCH).engine()).isEqualTo("RULE_BASED");
    }

    /** Only reachable if the Python service omits the field. Under-claiming is the honest
     * failure: never report something as AI-generated on the strength of a missing value. */
    @Test
    void aMissingEngineUnderClaimsRatherThanOverClaims() {
        InsightsResult result = new InsightsResult("Summary.", List.of(), null);

        assertThat(mapper.toResponse(7L, result, Instant.EPOCH).engine()).isEqualTo("RULE_BASED");
    }

    @Test
    void nullHighlightsBecomeAnEmptyListSoAClientNeverGuardsForNull() {
        InsightsResult result = new InsightsResult("Summary.", null, Engine.RULE_BASED);

        assertThat(mapper.toResponse(7L, result, Instant.EPOCH).highlights()).isEmpty();
    }

    @Test
    void highlightsAndDisclaimerCarryThrough() {
        InsightsResult result = new InsightsResult("Summary.",
                List.of(new Highlight("FX_EXPOSURE", "MEDIUM", "90.2% of holdings are non-INR.")),
                Engine.LLM);

        InsightsResponse response = mapper.toResponse(7L, result, Instant.EPOCH);

        assertThat(response.portfolioId()).isEqualTo(7L);
        assertThat(response.highlights()).singleElement().satisfies(highlight -> {
            assertThat(highlight.type()).isEqualTo("FX_EXPOSURE");
            assertThat(highlight.severity()).isEqualTo("MEDIUM");
            assertThat(highlight.message()).isEqualTo("90.2% of holdings are non-INR.");
        });
        assertThat(response.disclaimer()).isEqualTo("Generated commentary. Not investment advice.");
    }

    // ---- variants (§18) ------------------------------------------------------------------

    @Test
    void aSingleResultStillArrivesAsOneVariant() {
        InsightsResult result = new InsightsResult("Nothing notable.", List.of(), Engine.RULE_BASED);

        InsightsResponse response = mapper.toResponse(7L, result, Instant.EPOCH);

        assertThat(response.variants()).singleElement().satisfies(variant -> {
            assertThat(variant.engine()).isEqualTo("RULE_BASED");
            assertThat(variant.summary()).isEqualTo("Nothing notable.");
        });
    }

    @Test
    void bothReadingsAreCarriedInTheOrderTheyShouldBeOffered() {
        InsightsResponse response = mapper.toResponse(7L, List.of(
                new InsightsResult("Up 10%, led by RELIANCE.", List.of(), Engine.LLM),
                new InsightsResult("RELIANCE is 62.0% of market value.", List.of(), Engine.RULE_BASED)),
                Instant.EPOCH);

        assertThat(response.variants()).extracting(v -> v.engine())
                .containsExactly("AI_GENERATED", "RULE_BASED");
    }

    /** The top-level fields are what a client that knows nothing about variants reads, so they
     * must be the one to show by default — never the second-choice reading. */
    @Test
    void theTopLevelFieldsMirrorTheFirstVariant() {
        InsightsResponse response = mapper.toResponse(7L, List.of(
                new InsightsResult("Up 10%, led by RELIANCE.",
                        List.of(new Highlight("PERFORMANCE", "LOW", "Up 10%.")), Engine.LLM),
                new InsightsResult("RELIANCE is 62.0% of market value.", List.of(), Engine.RULE_BASED)),
                Instant.EPOCH);

        assertThat(response.engine()).isEqualTo("AI_GENERATED");
        assertThat(response.summary()).isEqualTo("Up 10%, led by RELIANCE.");
        assertThat(response.highlights()).hasSize(1);
        assertThat(response.variants().get(0).summary()).isEqualTo(response.summary());
    }

    @Test
    void aResponseWithNoVariantsAtAllIsRefusedRatherThanShippedEmpty() {
        assertThatThrownBy(() -> mapper.toResponse(7L, List.<InsightsResult>of(), Instant.EPOCH))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
