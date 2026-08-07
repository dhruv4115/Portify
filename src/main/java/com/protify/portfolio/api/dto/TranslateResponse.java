package com.protify.portfolio.api.dto;

import java.util.List;
import java.util.Map;

/**
 * The translated catalogue fragment.
 *
 * <p><b>{@code entries} always contains every key that was asked for.</b> A key that could not
 * be translated comes back holding its English source text, and its key is also listed in
 * {@link #untranslatedKeys}. That is the same contract the bundled catalogues already have —
 * an untranslated key renders the English sentence rather than a key name or a blank — so a
 * partial failure degrades a page's language rather than breaking its layout.
 *
 * @param engine           {@code AI_GENERATED} when a model produced these, {@code PASSTHROUGH}
 *                         when every value is the English source. Named honestly for the same
 *                         reason {@code InsightsResponse.engine} is: a reader is entitled to
 *                         know whether they are looking at a translation or at English.
 * @param untranslatedKeys the keys whose values are English, in request order. Empty on a full
 *                         success — never null, so a client can count it without a guard.
 */
public record TranslateResponse(
        String targetLanguage,
        String engine,
        Map<String, String> entries,
        List<String> untranslatedKeys) {

    public static final String ENGINE_AI = "AI_GENERATED";
    public static final String ENGINE_PASSTHROUGH = "PASSTHROUGH";
}
