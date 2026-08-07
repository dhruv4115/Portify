package com.protify.portfolio.api.dto;

import jakarta.validation.constraints.Pattern;

/**
 * API_CONTRACT.md §18's request body. Both fields optional — {@code GET} sends no body at all
 * and gets {@link #DEFAULT_HORIZON}/{@link #DEFAULT_TONE}.
 *
 * <p>Both are allowlisted rather than accepted as free text, because they are interpolated into
 * an LLM prompt by {@code services/insights/main.py}. An unconstrained string there is a prompt
 * injection surface: "concise" is a tone, and so, syntactically, is a paragraph instructing the
 * model to ignore what came before it. A closed set of tokens removes the question rather than
 * trying to sanitise an open one.
 */
public record GenerateInsightsRequest(
        @Pattern(regexp = "1D|1W|1M|3M|6M|1Y|YTD|ALL", message = "must be one of 1D, 1W, 1M, 3M, 6M, 1Y, YTD, ALL")
        String horizon,

        @Pattern(regexp = "concise|detailed|plain", message = "must be one of concise, detailed, plain")
        String tone) {

    public static final String DEFAULT_HORIZON = "1M";
    public static final String DEFAULT_TONE = "concise";

    /** What {@code GET} uses, and what {@code POST} falls back to field-by-field when the body
     * omits one but not the other. */
    public static GenerateInsightsRequest defaults() {
        return new GenerateInsightsRequest(DEFAULT_HORIZON, DEFAULT_TONE);
    }

    public String horizonOrDefault() {
        return horizon == null || horizon.isBlank() ? DEFAULT_HORIZON : horizon;
    }

    public String toneOrDefault() {
        return tone == null || tone.isBlank() ? DEFAULT_TONE : tone;
    }
}
