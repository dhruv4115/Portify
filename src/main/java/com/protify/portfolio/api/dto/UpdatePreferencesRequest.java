package com.protify.portfolio.api.dto;

import jakarta.validation.constraints.Pattern;

/**
 * The {@code PATCH /me/preferences} body. Every field is optional; an omitted field leaves the
 * stored value untouched, which is what PATCH means and what the {@code COALESCE} in
 * {@code AppUserRepository#updatePreferences} implements.
 *
 * <p>There is deliberately no way to clear a preference back to "unset". Nothing needs one:
 * {@code system}/{@code comfortable}/{@code full} are themselves the defaults, so the client's
 * reset button expresses itself as an ordinary update rather than needing a null sentinel that
 * JSON cannot distinguish from an absent field anyway.
 *
 * <p>{@code theme}, {@code density} and {@code motion} are allowlisted because the client
 * interpolates them into a DOM attribute; {@code language} is pattern-checked rather than
 * allowlisted so it does not have to be re-deployed in lockstep with the frontend's catalogue
 * list.
 */
public record UpdatePreferencesRequest(
        @Pattern(regexp = "light|dark|system", message = "must be one of light, dark, system")
        String theme,

        @Pattern(regexp = "[a-z]{2,3}(-[A-Za-z0-9]{2,8})?", message = "must be a BCP-47 language tag, e.g. en or pt-BR")
        String language,

        @Pattern(regexp = "comfortable|compact", message = "must be one of comfortable, compact")
        String density,

        @Pattern(regexp = "full|reduced", message = "must be one of full, reduced")
        String motion) {

    /** True when the body set nothing at all — a PATCH that changes nothing is a mistake worth
     * reporting rather than a silent no-op. */
    public boolean isEmpty() {
        return theme == null && language == null && density == null && motion == null;
    }
}
