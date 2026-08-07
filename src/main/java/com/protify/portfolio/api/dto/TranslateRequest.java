package com.protify.portfolio.api.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Map;

/**
 * {@code POST /i18n/translate}'s body: a batch of English source strings, keyed by the
 * catalogue key they belong to.
 *
 * <p>Keyed rather than a bare list because the response has to be merged back into a catalogue
 * by key. A positional array would make the client depend on the server preserving order
 * through a batching, caching and partial-failure path where preserving order is exactly the
 * kind of guarantee that quietly breaks.
 *
 * @param targetLanguage a BCP-47 tag. Not restricted to the nine bundled catalogues — the point
 *                       of this endpoint is the languages that are not bundled.
 * @param entries        catalogue key → English source text
 */
public record TranslateRequest(
        @NotBlank
        @Pattern(regexp = "[a-z]{2,3}(-[A-Za-z0-9]{2,8})?", message = "must be a BCP-47 language tag, e.g. ko or pt-BR")
        String targetLanguage,

        @NotEmpty
        @Size(max = MAX_ENTRIES, message = "must not contain more than " + MAX_ENTRIES + " entries")
        Map<String, @NotBlank @Size(max = MAX_TEXT_LENGTH) String> entries) {

    /**
     * One request carries at most a catalogue's worth of keys. The bundled English catalogue is
     * the reference point for what "a catalogue's worth" means; a request larger than that is
     * not a UI translating itself.
     */
    public static final int MAX_ENTRIES = 600;

    /** A UI string, not a document. Anything longer is not what this endpoint is for. */
    public static final int MAX_TEXT_LENGTH = 1000;
}
