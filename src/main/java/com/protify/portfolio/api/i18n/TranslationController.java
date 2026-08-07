package com.protify.portfolio.api.i18n;

import com.protify.portfolio.api.dto.TranslateRequest;
import com.protify.portfolio.api.dto.TranslateResponse;
import com.protify.portfolio.api.error.FeatureDisabledException;
import com.protify.portfolio.i18n.TranslationResult;
import com.protify.portfolio.i18n.TranslationService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /api/v1/i18n/translate} — on-demand UI translation for languages this build does
 * not ship a catalogue for.
 *
 * <p><b>This is deliberately not how the nine bundled languages work.</b> Those are static
 * files: free, instant, reviewable, and correct offline. This endpoint exists only for the
 * tenth language nobody has written a catalogue for yet, and a client should reach for it only
 * after {@code LANGUAGES} has no entry for what the user asked for. Routing the bundled
 * languages through here would make a solved problem cost money and a network round trip.
 *
 * <p>{@code POST} rather than {@code GET} because the request body is a catalogue of source
 * strings — far past what belongs in a query string — even though nothing is stored.
 *
 * <p>501 when the feature is off or unconfigured, never 404 (API_CONTRACT.md §18's rule), so a
 * client can tell "this deployment does not do translation" from "wrong URL" and fall back to
 * English deliberately.
 */
@RestController
@RequestMapping("/i18n")
public class TranslationController {

    private final TranslationService translationService;

    public TranslationController(TranslationService translationService) {
        this.translationService = translationService;
    }

    @PostMapping("/translate")
    public TranslateResponse translate(@Valid @RequestBody TranslateRequest request) {
        if (!translationService.isEnabled()) {
            throw new FeatureDisabledException("/errors/feature-disabled",
                    "On-demand translation is not enabled on this deployment.");
        }

        TranslationResult result = translationService.translate(request.targetLanguage(), request.entries());

        return new TranslateResponse(
                request.targetLanguage(),
                result.anyTranslated() ? TranslateResponse.ENGINE_AI : TranslateResponse.ENGINE_PASSTHROUGH,
                result.entries(),
                result.untranslatedKeys());
    }
}
