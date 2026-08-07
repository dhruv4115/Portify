package com.protify.portfolio.i18n;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.github.benmanes.caffeine.cache.Caffeine;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * The guarantee that matters: <b>every requested key comes back</b>, translated where possible
 * and English where not. A translation outage must degrade a page's language, never its
 * availability.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TranslationServiceTest {

    private static final Map<String, String> SOURCES = Map.of("nav.overview", "Overview");

    @Mock
    private TranslationClient translationClient;

    private TranslationService service;

    @BeforeEach
    void setUp() {
        given(translationClient.isEnabled()).willReturn(true);
        service = new TranslationService(translationClient, Caffeine.newBuilder().build());
    }

    @Test
    void translatedStringsComeBackAgainstTheirOwnKeys() {
        given(translationClient.translate(eq("ko"), any()))
                .willReturn(Optional.of(Map.of("nav.overview", "개요")));

        TranslationResult result = service.translate("ko", SOURCES);

        assertThat(result.entries()).containsEntry("nav.overview", "개요");
        assertThat(result.untranslatedKeys()).isEmpty();
        assertThat(result.anyTranslated()).isTrue();
    }

    /** The whole failure contract in one test: the model call fails outright and the caller
     * still gets a complete, renderable catalogue — in English. */
    @Test
    void aFailedCallFallsBackToEnglishRatherThanReturningNothing() {
        given(translationClient.translate(anyString(), any())).willReturn(Optional.empty());

        TranslationResult result = service.translate("ko", SOURCES);

        assertThat(result.entries()).containsEntry("nav.overview", "Overview");
        assertThat(result.untranslatedKeys()).containsExactly("nav.overview");
        assertThat(result.anyTranslated()).isFalse();
    }

    /** A model that drops one key from an otherwise-good batch must not cost the other keys —
     * and the dropped one still renders, in English. */
    @Test
    void aKeyTheModelOmittedIsFilledWithEnglishAndListed() {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("nav.overview", "Overview");
        sources.put("nav.settings", "Settings");
        given(translationClient.translate(anyString(), any()))
                .willReturn(Optional.of(Map.of("nav.overview", "개요")));

        TranslationResult result = service.translate("ko", sources);

        assertThat(result.entries())
                .containsEntry("nav.overview", "개요")
                .containsEntry("nav.settings", "Settings");
        assertThat(result.untranslatedKeys()).containsExactly("nav.settings");
        // Partially successful, not a passthrough — the response says AI_GENERATED honestly.
        assertThat(result.anyTranslated()).isTrue();
    }

    @Test
    void everyRequestedKeyIsPresentWhateverHappens() {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("a", "Alpha");
        sources.put("b", "Beta");
        sources.put("c", "Gamma");
        given(translationClient.translate(anyString(), any())).willReturn(Optional.empty());

        assertThat(service.translate("ko", sources).entries()).containsOnlyKeys("a", "b", "c");
    }

    /** Keyed by language and source text, so the second request for the same strings costs
     * nothing — these calls are billed per token and a catalogue is hundreds of strings. */
    @Test
    void aSecondRequestForTheSameStringsIsServedFromCache() {
        given(translationClient.translate(anyString(), any()))
                .willReturn(Optional.of(Map.of("nav.overview", "개요")));

        service.translate("ko", SOURCES);
        TranslationResult second = service.translate("ko", SOURCES);

        assertThat(second.entries()).containsEntry("nav.overview", "개요");
        verify(translationClient, times(1)).translate(anyString(), any());
    }

    /** Cached per language: the same English string asked for in a second language is a
     * different question and must reach the model again. */
    @Test
    void theCacheDoesNotServeOneLanguagesTranslationForAnother() {
        given(translationClient.translate(eq("ko"), any()))
                .willReturn(Optional.of(Map.of("nav.overview", "개요")));
        given(translationClient.translate(eq("ja"), any()))
                .willReturn(Optional.of(Map.of("nav.overview", "概要")));

        assertThat(service.translate("ko", SOURCES).entries()).containsEntry("nav.overview", "개요");
        assertThat(service.translate("ja", SOURCES).entries()).containsEntry("nav.overview", "概要");
    }
}
