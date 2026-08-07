package com.protify.portfolio.i18n;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Caching and batching in front of {@link TranslationClient}, and the place the English
 * fallback is actually applied.
 *
 * <p>The cache is keyed by <em>language and source text</em>, not by catalogue key. UI
 * catalogues repeat themselves — "Save", "Cancel", a currency name — across dozens of keys, and
 * a second user asking for the same language is asking for the identical strings. Keying on the
 * text means all of that collapses to one model call, which matters because these calls are
 * billed per token and a catalogue is several hundred strings.
 *
 * <p>Every path ends with every requested key present. A failure produces English, which is
 * exactly what the bundled catalogues already do for a key nobody has translated yet, so an
 * outage here looks like an incomplete translation rather than a broken page.
 */
@Service
public class TranslationService {

    /**
     * Batched so one unlucky request cannot become a single enormous prompt, and so a failure
     * costs one batch rather than the whole catalogue — the other batches still come back
     * translated and only their keys land in {@code untranslatedKeys}.
     */
    private static final int BATCH_SIZE = 50;

    /** Long, because a translation of a fixed English string does not go stale — this is a TTL
     * for correcting a bad translation after a redeploy, not for freshness. */
    private static final Duration CACHE_TTL = Duration.ofHours(24);

    private static final int CACHE_MAX_ENTRIES = 50_000;

    private final TranslationClient translationClient;
    private final Cache<CacheKey, String> cache;

    /** {@code @Autowired} because the test seam below makes this a two-constructor class, and
     * Spring will not guess which one to inject. */
    @Autowired
    public TranslationService(TranslationClient translationClient) {
        this(translationClient, Caffeine.newBuilder()
                .expireAfterWrite(CACHE_TTL)
                .maximumSize(CACHE_MAX_ENTRIES)
                .build());
    }

    /** Test-only seam: inject a cache with a controlled ticker, or an empty one per test. */
    TranslationService(TranslationClient translationClient, Cache<CacheKey, String> cache) {
        this.translationClient = translationClient;
        this.cache = cache;
    }

    public boolean isEnabled() {
        return translationClient.isEnabled();
    }

    public TranslationResult translate(String targetLanguage, Map<String, String> sources) {
        Map<String, String> out = new LinkedHashMap<>();
        Map<String, String> pending = new LinkedHashMap<>();

        for (Map.Entry<String, String> entry : sources.entrySet()) {
            String cached = cache.getIfPresent(new CacheKey(targetLanguage, entry.getValue()));
            if (cached != null) {
                out.put(entry.getKey(), cached);
            } else {
                pending.put(entry.getKey(), entry.getValue());
            }
        }

        boolean anyTranslated = !out.isEmpty();
        for (Map<String, String> batch : batches(pending)) {
            Optional<Map<String, String>> translated = translationClient.translate(targetLanguage, batch);
            if (translated.isEmpty()) {
                continue;
            }
            anyTranslated = true;
            translated.get().forEach((key, value) -> {
                out.put(key, value);
                cache.put(new CacheKey(targetLanguage, batch.get(key)), value);
            });
        }

        // Anything still missing — a failed batch, or a key the model dropped from one that
        // otherwise succeeded — becomes its English source, in the order it was requested.
        List<String> untranslated = new ArrayList<>();
        Map<String, String> complete = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : sources.entrySet()) {
            String value = out.get(entry.getKey());
            if (value == null) {
                untranslated.add(entry.getKey());
                complete.put(entry.getKey(), entry.getValue());
            } else {
                complete.put(entry.getKey(), value);
            }
        }

        return new TranslationResult(complete, List.copyOf(untranslated), anyTranslated);
    }

    private static List<Map<String, String>> batches(Map<String, String> pending) {
        List<Map<String, String>> batches = new ArrayList<>();
        Map<String, String> current = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : pending.entrySet()) {
            current.put(entry.getKey(), entry.getValue());
            if (current.size() == BATCH_SIZE) {
                batches.add(current);
                current = new LinkedHashMap<>();
            }
        }
        if (!current.isEmpty()) {
            batches.add(current);
        }
        return batches;
    }

    /** Language plus source text — see the class Javadoc for why not the catalogue key. */
    record CacheKey(String language, String sourceText) {
    }
}
